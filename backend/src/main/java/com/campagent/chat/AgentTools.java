package com.campagent.chat;
import com.campagent.auth.Actor;
import com.campagent.campsite.Campsites;
import com.campagent.campsite.PublicCampsites;
import com.campagent.knowledge.Retrieval;
import com.campagent.ticket.Tickets;
import java.util.*;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
public class AgentTools {
    final Actor actor;final String conversation;final Campsites camps;final Retrieval retrieval;final Tickets tickets;
    public final List<Map<String,Object>> citations=new ArrayList<>(),summary=new ArrayList<>();
    public List<Map<String,Object>> campsites=List.of();public Map<String,Object> draft;
    public List<Map<String,Object>> ownTickets;
    private final PublicCampsites publicCamps;
    private final String question;
    public PublicCampsites.Search publicSearch;
    public PublicCampsites.Detail publicDetail;
    public PublicCampsites.Answer publicAnswer;
    public AgentTools(Actor actor,String conversation,Campsites camps,Retrieval retrieval,Tickets tickets,PublicCampsites publicCamps,String question){this.actor=actor;this.conversation=conversation;this.camps=camps;this.retrieval=retrieval;this.tickets=tickets;this.publicCamps=publicCamps;this.question=question;}

    @Tool(description="查找真实地图营地。必须先确定地级市或直辖市，缺城市先追问。关键词为营地名称、露营地或房车营地。")
    public Map<String, Object> searchPublicCampsites(String city, String keyword) {
        publicSearch = publicCamps.search(actor, conversation, city, keyword);
        summary.add(Map.of("name", "searchPublicCampsites", "count", publicSearch.places().size()));
        return Map.of("count", publicSearch.places().size());
    }

    @Tool(description="查看本次会话真实营地搜索结果的地图地址、坐标等详情。position 为1至5的序号，0表示刚选中的地点。先搜索再使用。")
    public Map<String, Object> getPublicCampsite(int position) {
        publicDetail = publicCamps.chatDetail(actor, conversation, position, question);
        summary.add(Map.of("name", "getPublicCampsite"));
        return Map.of("status", "详情已核对");
    }

    @Tool(description="核对本次会话选中真实营地的宠物、退订、价格、余位或预订规则。position 为1至5的序号，0表示刚选中的地点。")
    public Map<String, Object> askPublicCampsiteRules(int position) {
        publicAnswer = publicCamps.chatRules(actor, conversation, position, question);
        summary.add(Map.of("name", "askPublicCampsiteRules"));
        return Map.of("answer", publicAnswer.answer());
    }

    public String publicResponse() {
        if (publicSearch != null) return publicSearch.places().isEmpty()
                ? "本次在该城市未查到符合条件的营地候选。可调整名称或关键词重新查询。"
                : "找到 " + publicSearch.places().size() + " 个高德地图营地候选。请查看下方卡片，可按序号查看详情或追问规则。";
        if (publicDetail != null) return "已核对所选地点的地图详情，请查看下方卡片。营地经营规则需另行核实。";
        if (publicAnswer != null) return publicAnswer.answer();
        return null;
    }
    @Tool(description="按地区、设施或营地名称筛选营地；可选值用空字符串。不查询库存。") public List<Map<String,Object>> searchCampsites(@ToolParam(description="城市，如杭州；不限为空")String city,@ToolParam(description="一种设施，如洗手间；不限为空")String facility,@ToolParam(description="营地名称关键词；不限为空")String keyword){
        campsites=camps.search(city,facility,keyword);summary.add(Map.of("name","searchCampsites","count",campsites.size()));return campsites;
    }
    @Tool(description="根据已知营地 ID 查询当前营地信息，不推断未提供的数据。") public Map<String,Object> getCampsite(String campsiteId){var c=camps.get(campsiteId);campsites=List.of(c);summary.add(Map.of("name","getCampsite","campsiteId",campsiteId));return c;}
    @Tool(description="查询指定演示营地的知识和规则。先核实营地 ID，未收录时不套用其他营地资料。比较多个营地请分别查询。")
    public List<Map<String,Object>> searchKnowledge(String question,@ToolParam(description="已核实的本地营地 ID；无法确定时传空字符串")String campsiteId){
        var r=campsiteId==null||campsiteId.isBlank()?List.<Map<String,Object>>of():retrieval.search(actor,question,campsiteId);
        for(var c:r)if(citations.stream().noneMatch(x->x.get("id").equals(c.get("id"))))citations.add(c);
        summary.add(Map.of("name","searchKnowledge","count",r.size()));return r;
    }
    @Tool(description="仅当用户明确提出咨询/投诉/建议提交意图且信息齐全时准备工单草稿，不提交正式工单。内容变更会替换旧草稿。")
    public Map<String,Object> prepareTicket(String campsiteId,@ToolParam(description="咨询、投诉或建议")String kind,String title,String description){
        draft=tickets.preview(actor,conversation,campsiteId,kind,title,description);summary.add(Map.of("name","prepareTicket","draftId",draft.get("id")));return draft;
    }
    @Tool(description="查询当前登录用户自己的工单，不能查询其他用户。") public List<Map<String,Object>> queryMyTickets(){
        ownTickets=tickets.mine(actor);summary.add(Map.of("name","queryMyTickets","count",ownTickets.size()));return ownTickets;
    }
}

