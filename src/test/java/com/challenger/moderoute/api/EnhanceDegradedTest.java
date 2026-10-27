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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 精判引擎不可达时：仍然 200，action=DEGRADED，规则降级仍给出可用骨架。
 */
@SpringBootTest(properties = {
        "moderoute.recall.embedding=mock",
        "moderoute.decider.provider=jev-local",
        "moderoute.decider.base-url=http://127.0.0.1:1",
        "moderoute.decider.connect-timeout-ms=200",
        "moderoute.decider.timeout-ms=500",
        "moderoute.audit.dir=target/test-audit-degraded",
        "moderoute.recall.store=json",
        "moderoute.recall.cache-dir=target/test-vectors-degraded",
        "moderoute.cards-dir=data/cards"
})
@AutoConfigureMockMvc
class EnhanceDegradedTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper mapper;

    @Test
    void 决策引擎不可达时降级但仍返回可用骨架() throws Exception {
        String body = mockMvc.perform(post("/v1/enhance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"task\":\"为三条产线排布五张订单，订单带最晚完成日\",\"traceId\":\"d-1\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode json = mapper.readTree(body);
        assertThat(json.path("meta").path("action").asText()).isEqualTo("DEGRADED");
        assertThat(json.path("prompt").asText()).isNotEmpty();
        assertThat(json.path("prompt").asText()).contains("【推理骨架：");
        assertThat(json.path("meta").path("libraryVersion").asText()).matches("[0-9a-f]{12}");
    }
}
