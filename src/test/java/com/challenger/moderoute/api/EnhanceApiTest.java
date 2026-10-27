package com.challenger.moderoute.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "moderoute.recall.embedding=mock",
        "moderoute.decider.provider=mock",
        "moderoute.override-enabled=true",
        "moderoute.audit.dir=target/test-audit",
        "moderoute.recall.sqlite-path=target/test-vectors-api.db",
        "moderoute.recall.extension-path=lib/native/vec0.dll",
        "moderoute.cards-dir=data/cards"
})
@AutoConfigureMockMvc
class EnhanceApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper mapper;

    @Test
    void 健康检查与卡片列表可用() throws Exception {
        String health = mockMvc.perform(get("/healthz"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode healthJson = mapper.readTree(health);
        assertThat(healthJson.path("cardCount").asInt()).isEqualTo(48);
        assertThat(healthJson.path("status").asText()).isEqualTo("UP");
        assertThat(healthJson.path("libraryVersion").asText()).matches("[0-9a-f]{12}");

        String cards = mockMvc.perform(get("/v1/cards"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(cards).path("count").asInt()).isEqualTo(48);
    }

    @Test
    void 正常请求返回prompt与meta() throws Exception {
        JsonNode body = enhance("{\"task\":\"为三条产线排布五张订单，订单有最晚完成日\",\"traceId\":\"t-1\"}");

        assertThat(body.path("prompt").isTextual()).isTrue();
        assertThat(body.path("traceId").asText()).isEqualTo("t-1");
        assertThat(body.path("meta").path("action").asText()).isIn("INJECT", "FALLBACK");
        assertThat(body.path("meta").path("libraryVersion").asText()).matches("[0-9a-f]{12}");
        assertThat(body.path("meta").path("confidence").asDouble()).isBetween(0.0, 1.0);
    }

    @Test
    void 相同任务与相同库版本输出逐字节一致() throws Exception {
        String request = "{\"task\":\"订单 O1 与 O2 争用同一台设备，请给出排产\",\"traceId\":\"t-det\"}";
        String first = enhance(request).toString();
        String second = enhance(request).toString();
        assertThat(second).isEqualTo(first);
    }

    @Test
    void 空任务返回400() throws Exception {
        String body = mockMvc.perform(post("/v1/enhance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"task\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(body).path("error").path("code").asText()).isEqualTo("INVALID_TASK");
    }

    @Test
    void 超长任务返回422() throws Exception {
        String longTask = "啊".repeat(4001);
        String body = mockMvc.perform(post("/v1/enhance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"task\":\"" + longTask + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(body).path("error").path("code").asText()).isEqualTo("TASK_TOO_LONG");
    }

    @Test
    void 非法topK返回400() throws Exception {
        String body = mockMvc.perform(post("/v1/enhance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"task\":\"排产\",\"options\":{\"topK\":2}}"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(body).path("error").path("code").asText()).isEqualTo("INVALID_OPTIONS");
    }

    @Test
    void 非法JSON返回400() throws Exception {
        String body = mockMvc.perform(post("/v1/enhance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json}"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(body).path("error").path("code").asText()).isEqualTo("INVALID_JSON");
    }

    @Test
    void 指定卡覆盖生成该卡渲染文本() throws Exception {
        JsonNode body = enhance("{\"task\":\"任意任务\",\"override\":{\"mode\":\"forceCard\","
                + "\"cardId\":\"planning-capacity-first\"}}");
        assertThat(body.path("meta").path("action").asText()).isEqualTo("INJECT");
        assertThat(body.path("meta").path("cardId").asText()).isEqualTo("planning-capacity-first");
        assertThat(body.path("prompt").asText()).contains("【推理骨架：容量先行排布】");
    }

    @Test
    void 未知卡返回400() throws Exception {
        String body = mockMvc.perform(post("/v1/enhance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"task\":\"任意任务\",\"override\":{\"mode\":\"forceCard\",\"cardId\":\"nope-1\"}}"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(body).path("error").path("code").asText()).isEqualTo("UNKNOWN_CARD");
    }

    @Test
    void baseline返回空串而不是null() throws Exception {
        JsonNode body = enhance("{\"task\":\"任意任务\",\"override\":{\"mode\":\"baseline\"}}");
        assertThat(body.path("prompt").isNull()).isFalse();
        assertThat(body.path("prompt").asText()).isEmpty();
        assertThat(body.path("meta").path("action").asText()).isEqualTo("FALLBACK");
    }

    @Test
    void placebo注入等长通用骨架() throws Exception {
        JsonNode body = enhance("{\"task\":\"为三条产线排布五张订单\",\"override\":{\"mode\":\"placebo\"}}");
        assertThat(body.path("meta").path("action").asText()).isEqualTo("INJECT");
        assertThat(body.path("prompt").asText()).contains("通用分步推进");
        assertThat(body.path("meta").path("cardId").isNull()).isTrue();
    }

    @Test
    void randomCard按种子确定() throws Exception {
        String request = "{\"task\":\"为三条产线排布五张订单\",\"override\":{\"mode\":\"randomCard\",\"seed\":42}}";
        String first = enhance(request).path("meta").path("cardId").asText();
        String second = enhance(request).path("meta").path("cardId").asText();
        assertThat(second).isEqualTo(first);
    }

    private JsonNode enhance(String json) throws Exception {
        String body = mockMvc.perform(post("/v1/enhance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body);
    }
}
