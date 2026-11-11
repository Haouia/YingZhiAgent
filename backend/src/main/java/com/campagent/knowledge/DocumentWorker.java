package com.campagent.knowledge;
import com.campagent.common.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Async;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.beans.factory.annotation.Value;
@Service
public class DocumentWorker {
    private final Db db;private final Documents documents;private final Cloud cloud;private final boolean demo;
    public DocumentWorker(Db db,Documents documents,Cloud cloud,@Value("${camp.mode}")String mode){this.db=db;this.documents=documents;this.cloud=cloud;demo=mode.equals("demo");}
    @Async("documentExecutor") public void parse(String id){
        try{
            var row=db.one("select * from knowledge_version where id=?",id);
            if(!"PROCESSING".equals(row.get("status")))return;
            Path file=documents.file(id);String name=row.get("filename").toString().toLowerCase();
            String text;
            if(name.endsWith(".pdf")){
                try(var pdf=Loader.loadPDF(file.toFile())){
                    if(pdf.getNumberOfPages()>30)throw ApiException.bad("PDF 最多 30 页");
                    StringBuilder out=new StringBuilder();var stripper=new PDFTextStripper();var renderer=new PDFRenderer(pdf);
                    for(int p=0;p<pdf.getNumberOfPages();p++){stripper.setStartPage(p+1);stripper.setEndPage(p+1);String page=stripper.getText(pdf);
                        if(page.isBlank()){
                            var box=pdf.getPage(p).getCropBox();double width=Math.ceil(box.getWidth()*120d/72),height=Math.ceil(box.getHeight()*120d/72);
                            if(!Double.isFinite(width)||!Double.isFinite(height)||width<=0||height<=0||width*height>20000000)throw ApiException.bad("PDF 页面渲染像素过大");
                            if(demo)throw ApiException.bad("扫描页 OCR 需要 cloud 模式和模型 Key");
                            var bytes=new ByteArrayOutputStream();var image=renderer.renderImageWithDPI(p,120);
                            try{javax.imageio.ImageIO.write(image,"png",bytes);}finally{image.flush();}
                            page=cloud.ocr(bytes.toByteArray(),"image/png");
                        }
                        out.append("【第 ").append(p+1).append(" 页】\n").append(page).append("\n\n");
                        if(out.length()>150000)throw ApiException.bad("PDF 内容过长");
                    }text=out.toString();
                }
            }else if(name.endsWith(".docx")){
                try(var in=Files.newInputStream(file);var doc=new XWPFDocument(in)){
                    StringBuilder out=new StringBuilder();int n=0;for(IBodyElement e:doc.getBodyElements()){
                        if(e instanceof XWPFParagraph p)out.append("【段落 ").append(++n).append("】\n").append(p.getText()).append("\n\n");
                        if(e instanceof XWPFTable t)out.append("【表格 ").append(++n).append("】\n").append(t.getText()).append("\n\n");
                        if(out.length()>150000)throw ApiException.bad("Word 内容过长");
                    }text=out.toString();
                }
            }else if(name.endsWith(".png")||name.endsWith(".jpg")||name.endsWith(".jpeg")){
                if(demo)throw ApiException.bad("图片 OCR 需要 cloud 模式和模型 Key");
                try(var input=javax.imageio.ImageIO.createImageInputStream(file.toFile())){
                    var readers=javax.imageio.ImageIO.getImageReaders(input);if(!readers.hasNext())throw ApiException.bad("图片格式无效");
                    var reader=readers.next();try{reader.setInput(input);if((long)reader.getWidth(0)*reader.getHeight(0)>20000000)throw ApiException.bad("图片像素过大");}finally{reader.dispose();}
                }
                text=cloud.ocr(Files.readAllBytes(file),name.endsWith(".png")?"image/png":"image/jpeg");
            }else text=StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(file))).toString();
            documents.saveExtracted(id,text);
        }catch(Exception e){String reason=e instanceof ApiException?e.getMessage():"解析失败，请确认文件内容与扩展名一致";db.update("update knowledge_version set status='FAILED',error_message=? where id=? and status='PROCESSING'",reason,id);}
    }
}

