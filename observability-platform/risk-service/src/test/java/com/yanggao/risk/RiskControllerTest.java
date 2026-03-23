package com.yanggao.risk;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RiskController.class)
class RiskControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void evaluate_lowRisk() throws Exception {
        mockMvc.perform(post("/api/risk/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":50,"currency":"USD"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskScore").value(10))
                .andExpect(jsonPath("$.riskLevel").value("LOW"));
    }

    @Test
    void evaluate_mediumRisk() throws Exception {
        mockMvc.perform(post("/api/risk/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":500,"currency":"USD"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskScore").value(50))
                .andExpect(jsonPath("$.riskLevel").value("MEDIUM"));
    }

    @Test
    void evaluate_highRisk() throws Exception {
        mockMvc.perform(post("/api/risk/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":2000,"currency":"GBP"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskScore").value(90))
                .andExpect(jsonPath("$.riskLevel").value("HIGH"));
    }

    @Test
    void evaluate_boundaryAt100() throws Exception {
        mockMvc.perform(post("/api/risk/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":100,"currency":"EUR"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskScore").value(50))
                .andExpect(jsonPath("$.riskLevel").value("MEDIUM"));
    }

    @Test
    void evaluate_boundaryAt1000() throws Exception {
        mockMvc.perform(post("/api/risk/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":1000,"currency":"EUR"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskScore").value(50))
                .andExpect(jsonPath("$.riskLevel").value("MEDIUM"));
    }
}
