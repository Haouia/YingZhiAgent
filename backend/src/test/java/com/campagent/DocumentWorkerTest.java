package com.campagent;

import com.campagent.common.Db;
import com.campagent.knowledge.*;
import java.nio.file.Path;
import java.util.Map;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.mockito.Mockito.*;

class DocumentWorkerTest {
    @TempDir Path directory;
    @Test void oversizedBlankPdfFailsBeforeRenderingOrOcr() throws Exception {
        var db=mock(Db.class);var documents=mock(Documents.class);var cloud=mock(Cloud.class);
        Path file=directory.resolve("oversized.pdf");
        try(var pdf=new PDDocument()){pdf.addPage(new PDPage(new PDRectangle(10000,10000)));pdf.save(file.toFile());}
        when(db.one("select * from knowledge_version where id=?","version")).thenReturn(Map.of("status","PROCESSING","filename","oversized.pdf"));
        when(documents.file("version")).thenReturn(file);
        new DocumentWorker(db,documents,cloud,"cloud").parse("version");
        verify(db).update("update knowledge_version set status='FAILED',error_message=? where id=? and status='PROCESSING'","PDF 页面渲染像素过大","version");
        verifyNoInteractions(cloud);
        verify(documents,never()).saveExtracted(anyString(),anyString());
    }
    @Test void normalBlankPdfStillUsesOcr() throws Exception {
        var db=mock(Db.class);var documents=mock(Documents.class);var cloud=mock(Cloud.class);
        Path file=directory.resolve("normal.pdf");
        try(var pdf=new PDDocument()){pdf.addPage(new PDPage());pdf.save(file.toFile());}
        when(db.one("select * from knowledge_version where id=?","version")).thenReturn(Map.of("status","PROCESSING","filename","normal.pdf"));
        when(documents.file("version")).thenReturn(file);
        when(cloud.ocr(any(byte[].class),eq("image/png"))).thenReturn("测试原文");
        new DocumentWorker(db,documents,cloud,"cloud").parse("version");
        verify(documents).saveExtracted(eq("version"),contains("测试原文"));
    }
}
