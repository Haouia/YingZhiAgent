package com.campagent.campsite;

import com.campagent.auth.Actor;
import com.campagent.common.ApiException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlaceIdentityTest {
    final Actor user = new Actor("u", "USER", "test");
    final AmapMcp map = mock(AmapMcp.class);
    final PublicCampsites service = new PublicCampsites(map);
    Map<String,Object> poi(String id, String name) {
        return Map.of("id", id, "name", name, "city", "杭州市", "address", "测试路" + id,
                "type", "体育休闲服务;露营地", "typecode", "080300");
    }
    PublicCampsites.Search search(String query, List<?> places) {
        when(map.call(eq("u"), eq("maps_text_search"), anyMap())).thenReturn((Map) Map.of("pois", places));
        return service.search(user, "杭州", query);
    }
    @Test void excludesAncillaryFacilitiesButKeepsTheActualCamp() {
        var result = search("露营地", List.of(poi("A", "北迹营地充电站"), poi("B", "北迹营地服务中心"),
                poi("C", "北迹营地"), poi("D", "北迹营地停车场")));
        assertEquals(List.of("C"), result.places().stream().map(PublicCampsites.Place::id).toList());
    }
    @Test void exactMatchBeyondFirstFiveWinsAndWrongBranchIsExcluded() {
        var places = new ArrayList<Map<String,Object>>();
        for (int i=0;i<6;i++) places.add(poi("X"+i, "其他品牌"+i+"营地"));
        places.add(poi("WRONG", "自由旷野2号营地"));
        places.add(poi("RIGHT", "自由旷野１号营地"));
        var result = search("自由旷野1号营地", places);
        assertEquals(List.of("RIGHT"), result.places().stream().map(PublicCampsites.Place::id).toList());
    }
    @Test void categoryCanIdentifyCampWithoutKeywordAndMissingIdentityIsRejected() {
        var result = search("露营地", List.of(poi("A", "奥水森屿"), Map.of("id", "B", "name", "未知营地"),
                Map.of("id", "C", "name", "跨市营地", "city", "成都", "type", "露营地")));
        assertEquals(List.of("A"), result.places().stream().map(PublicCampsites.Place::id).toList());
    }
    @Test void sameBrandDifferentBranchesAndAddressesAreNotMerged() {
        var result = search("北迹", List.of(poi("A", "北迹东营地"), poi("B", "北迹西营地"), poi("A", "北迹东营地")));
        assertEquals(List.of("A", "B"), result.places().stream().map(PublicCampsites.Place::id).toList());
    }
    @Test void reviewedLongNamesRemainCandidatesWithoutChangingPoiIdentity() {
        for(var pair:List.of(List.of("太阳岛栖凤台露营基地","太阳岛露营地(栖凤台营地)"),
                List.of("十里埠黄金水岸露营地","黄金水岸江景营地"),
                List.of("北纬23°8'森林营地旅游区","北纬23度8森林营地"),
                List.of("SanfoHood植物与行星微度假营地","SANFOHOOD植物与行星露营地"))) {
            var result=search(pair.get(0),List.of(poi("A",pair.get(1))));
            assertEquals(List.of("A"),result.places().stream().map(PublicCampsites.Place::id).toList(),pair.getFirst());
        }
    }
    @Test void spacesAndPunctuationDoNotHideAnExactMatch() {
        var result=search("云 溪·1号营地",List.of(poi("B","云溪2号营地"),poi("A","云溪１号营地")));
        assertEquals(List.of("A"),result.places().stream().map(PublicCampsites.Place::id).toList());
    }
    @Test void detailsApplyTheSameFacilityFilter() {
        var result = search("露营地", List.of(poi("A", "北迹营地")));
        when(map.call(eq("u"), eq("maps_search_detail"), anyMap())).thenReturn((Map) poi("A", "北迹营地充电站"));
        assertThrows(ApiException.class, () -> service.detail(user, result.searchId(), "A"));
    }
}
