package com.campagent.campsite;

import com.campagent.auth.Actor;
import com.campagent.common.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class PublicCampsites {
    private static final String SOURCE = "高德地图";
    private static final Pattern CAMP = Pattern.compile("营地|露营(?:基地|公园|小镇|村|庄园|乐园)|房车(?:公园|小镇)|汽车露营");
    private static final Pattern OTHER = Pattern.compile("装备|用品|专卖店|旗舰店|停车场|售票处|管理处|卫生间|公共厕所|入口|出口|烤肉|烧烤|充电站|充电桩|换电站|服务中心|游客中心|军事|军训");
    private static final Pattern ORDINAL = Pattern.compile("第([0-9]+|[零一二三四五六七八九十百]+)个");
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PublicCampsites.class);
    private final AmapMcp amap;
    // shortcut: selections live in one process, use shared storage if replicas are deployed.
    private final Map<Scope, Selection> selections = new ConcurrentHashMap<>();

    public PublicCampsites(AmapMcp amap) {
        this.amap = amap;
    }

    public record Place(String id, String name, String address) {}
    public record Search(String searchId, String city, String source, Instant queriedAt, List<Place> places) {}
    public record Detail(String id, String name, String city, String address, String location,
                         String category, String source, Instant queriedAt) {}
    public record Answer(String placeId, String answer) {}
    private record Scope(String user, String conversation) {}
    private record Selection(String id, String city, List<String> places, Instant until, String selected) {}

    public Search search(Actor actor, String city, String keyword) {
        return search(actor, "", city, keyword);
    }

    public Search search(Actor actor, String conversation, String city, String keyword) {
        String region = input(city, 40, "请输入城市名称");
        String query = input(keyword, 120, "请输入营地名称或关键词");
        var payload = amap.call(actor.id(), "maps_text_search", Map.of("city", region, "keywords", query, "citylimit", true));
        if (!(payload.get("pois") instanceof List<?> pois)) throw new ApiException(HttpStatus.BAD_GATEWAY, "地图搜索结果格式无效");

        var places = new ArrayList<Place>();
        var seen = new HashSet<String>();
        int cityRejected=0, identityRejected=0, relevanceRejected=0;
        for (Object entry : pois) {
            if (!(entry instanceof Map<?, ?> poi)) continue;
            String id = text(poi, "id");
            String name = text(poi, "name");
            String typeCode = text(poi, "typecode");
            String returnedCity = text(poi, "city");
            if (returnedCity.isBlank()) returnedCity = text(poi, "cityname");
            if (!returnedCity.isBlank() && !sameCity(region, returnedCity)) { cityRejected++; continue; }
            if (!id.matches("[A-Za-z0-9_-]{1,64}") || !isCamp(name, typeCode, text(poi,"type")) || text(poi,"address").isBlank()) { identityRejected++; continue; }
            if (relevance(query, name, region) < 0) { relevanceRejected++; continue; }
            if (!seen.add(id)) continue;
            places.add(new Place(id, name, text(poi, "address")));

        }
        places.sort(java.util.Comparator.comparingInt(place -> relevance(query, place.name(), region)));
        if (places.size()>5) places.subList(5,places.size()).clear();
        log.info("mapSearch requestId={} raw={} cityRejected={} identityRejected={} relevanceRejected={} returned={}", org.slf4j.MDC.get("requestId"),pois.size(),cityRejected,identityRejected,relevanceRejected,places.size());
        Instant now = Instant.now();
        selections.values().removeIf(selection -> !selection.until().isAfter(now));
        var selection = new Selection(UUID.randomUUID().toString(), region,
                places.stream().map(Place::id).toList(), now.plus(Duration.ofMinutes(10)), null);
        selections.put(new Scope(actor.id(), conversation), selection);
        return new Search(selection.id(), region, SOURCE, now, List.copyOf(places));
    }

    public Detail detail(Actor actor, String searchId, String poiId) {
        return detail(actor, "", searchId, poiId);
    }

    private Detail detail(Actor actor, String conversation, String searchId, String poiId) {
        Selection selection = selection(actor, conversation, searchId);
        if (!selection.places().contains(poiId)) throw ApiException.missing();
        var poi = amap.call(actor.id(), "maps_search_detail", Map.of("id", poiId));
        String city = text(poi, "city");
        String name = text(poi, "name");
        String category = text(poi, "type");
        if (!poiId.equals(text(poi, "id")) || !sameCity(selection.city(), city)
                || !isCamp(name, text(poi,"typecode"), category) || text(poi,"address").isBlank()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "该候选的营地身份或城市未能核实，请重新查找");
        }
        remember(actor, conversation, selection, poiId);
        return new Detail(poiId, name, city, text(poi, "address"), text(poi, "location"), category, SOURCE, Instant.now());
    }

    public Answer followUp(Actor actor, String searchId, String poiId, String question) {
        Selection selection = selection(actor, "", searchId);
        String selected = selected(selection, poiId, question);
        remember(actor, "", selection, selected);
        return missingRules(selected);
    }

    public Detail chatDetail(Actor actor, String conversation, int position, String question) {
        Selection selection = current(actor, conversation);
        String selected = selected(selection, position(selection, position), question);
        return detail(actor, conversation, selection.id(), selected);
    }

    public Answer chatRules(Actor actor, String conversation, int position, String question) {
        Selection selection = current(actor, conversation);
        String selected = selected(selection, position(selection, position), question);
        remember(actor, conversation, selection, selected);
        return missingRules(selected);
    }

    private static String position(Selection selection, int position) {
        if (position == 0) return selection.selected();
        if (position < 1 || position > selection.places().size()) throw ApiException.bad("当前结果中没有这个序号");
        return selection.places().get(position - 1);
    }

    private static String selected(Selection selection, String poiId, String question) {
        var matcher = ORDINAL.matcher(input(question, 2000, "请输入要核对的问题"));
        String selected = poiId;
        if (matcher.find()) {
            String ordinal = matcher.group(1);
            int position = List.of("一", "二", "三", "四", "五").indexOf(ordinal);
            if (ordinal.matches("[1-5]")) position = Integer.parseInt(ordinal) - 1;
            if (position < 0 || position >= selection.places().size()) throw ApiException.bad("当前结果中没有这个序号");
            selected = selection.places().get(position);
        }
        if (selected == null || selected.isBlank()) {
            if (selection.places().size() != 1) throw ApiException.bad("请先选择要问的营地");
            selected = selection.places().getFirst();
        }
        if (!selection.places().contains(selected)) throw ApiException.missing();
        return selected;
    }

    private static Answer missingRules(String selected) {
        return new Answer(selected, "该地点暂无已审核发布的规则资料。宠物、价格、退订和营位余量请向营地运营方核实。");
    }

    private void remember(Actor actor, String conversation, Selection selection, String selected) {
        var updated = new Selection(selection.id(), selection.city(), selection.places(), selection.until(), selected);
        if (!selection.until().isAfter(Instant.now()) || !selections.replace(new Scope(actor.id(), conversation), selection, updated)) {
            throw new ApiException(HttpStatus.CONFLICT, "查询结果已变更，请重新选择营地");
        }
    }

    private Selection selection(Actor actor, String conversation, String searchId) {
        var selection = current(actor, conversation);
        if (!selection.id().equals(searchId)) throw ApiException.missing();
        return selection;
    }

    private Selection current(Actor actor, String conversation) {
        var selection = selections.get(new Scope(actor.id(), conversation));
        if (selection == null || !selection.until().isAfter(Instant.now())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "查询结果已失效或不可访问，请重新搜索");
        }
        return selection;
    }

    private static String input(String value, int limit, String message) {
        if (value == null || value.isBlank() || value.strip().length() > limit) throw ApiException.bad(message);
        return value.strip();
    }

    private static boolean sameCity(String expected, String actual) {
        return !actual.isBlank() && expected.replaceFirst("市$", "").equals(actual.replaceFirst("市$", ""));
    }

    private static String text(Map<?, ?> value, String key) {
        return value.get(key) instanceof String string ? string.strip() : "";
    }

    private static boolean isCamp(String name, String typeCode, String category) {
        if (OTHER.matcher(name).find() || category.contains("购物服务") || category.contains("餐饮服务")
                || category.contains("充电") || typeCode.matches("^(05|06|15|20).*$")) return false;
        boolean explicit = category.matches(".*(露营|营地|房车).*");
        boolean leisure = typeCode.matches("^(08|11).*$") || category.matches(".*(体育休闲服务|风景名胜).*");
        return !name.isBlank() && (explicit || (CAMP.matcher(name).find() && leisure));
    }

    private static String normalize(String text) {
        return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC).replaceAll("[\\p{P}\\p{Z}\\s]", "");
    }

    private static String stem(String text) {
        return text.replaceFirst("(?:汽车露营地|房车营地|露营基地|露营地|营地)$", "");
    }

    private static int relevance(String query, String name, String city) {
        String q=normalize(query), n=normalize(name);
        if (java.util.Set.of("露营地","营地","露营","房车营地","汽车露营地","房车").contains(q)) return 2;
        if (q.equals(n)) return 0;
        var ordinal=Pattern.compile("([0-9]+|[一二三四五六七八九十])号");
        var a=ordinal.matcher(q); var b=ordinal.matcher(n);
        if(a.find() && b.find() && !number(a.group(1)).equals(number(b.group(1)))) return -1;
        String region=normalize(city).replaceFirst("市$", "");
        if(q.startsWith(region)) q=q.substring(region.length()).replaceFirst("^市", "");
        String qs=stem(q), ns=stem(n);
        if(qs.length()>=2 && ns.length()>=2 && (n.contains(qs) || q.contains(ns))) return 1;
        var left=grams(qs); var right=grams(ns);
        long shared=left.stream().filter(right::contains).count();
        return shared>=2 && shared>=0.6*Math.min(left.size(),right.size()) ? 2 : -1;
    }

    // Candidate relevance is not proof that two POIs are the same campsite.
    private static java.util.Set<String> grams(String text) {
        String core=text.toLowerCase(java.util.Locale.ROOT).replaceAll("露营基地|露营地|微度假|旅游区|营地", "");
        var grams=new HashSet<String>();
        var matcher=Pattern.compile("[\\p{IsHan}]+|[a-z]+|[0-9]+").matcher(core);
        while(matcher.find()) {
            String part=matcher.group();
            if(part.matches("[a-z0-9]+")) { grams.add(part); continue; }
            for(int i=0;i+1<part.length();i++) grams.add(part.substring(i,i+2));
        }
        return grams;
    }

    private static String number(String text) {
        int index=java.util.List.of("一","二","三","四","五","六","七","八","九","十").indexOf(text);
        return index<0 ? text : String.valueOf(index+1);
    }
}
