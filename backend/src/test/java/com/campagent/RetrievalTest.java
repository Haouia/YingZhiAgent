package com.campagent;

import com.campagent.knowledge.Retrieval;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RetrievalTest {
    @Test void bm25KeepsFrequencyRankingAndStableTies() {
        var chunks=List.<Map<String,Object>>of(Map.of("id","c","content","pets"),Map.of("id","b","content","pets pets"),Map.of("id","a","content","pets pets"),Map.of("id","d","content","unrelated"));
        assertEquals(List.of("a","b","c"),Retrieval.bm25("pets",chunks).stream().map(c->c.get("id")).toList());
        assertEquals(List.of(),Retrieval.bm25("不存在",chunks));
        assertEquals(List.of(),Retrieval.bm25("",chunks));
    }
    @Test void supportedCorpusDoesNotRepeatDocumentFrequencyScans() {
        var chunks=new ArrayList<Map<String,Object>>();
        String text="森林竹溪山谷步行活动设施须知。".repeat(35)+"宠物牵引";
        for(int i=0;i<5000;i++)chunks.add(Map.of("id","chunk-"+i,"content",text));
        assertTimeout(Duration.ofSeconds(5),()->assertEquals(5000,Retrieval.bm25("宠物牵引",chunks).size()));
    }
}
