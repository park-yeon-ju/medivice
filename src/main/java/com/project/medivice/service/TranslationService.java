package com.project.medivice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * UC28·29 진료용 보고서를 영어로 낼 때, 사용자가 한국어로 직접 적은 증상명·메모까지 함께
 * 영어로 옮겨주는 DeepL 연동. AI 요약 문장(narrative)은 이미 AiClient가 language별로 알아서
 * 만들어 주지만, symptoms/note는 사용자가 쓴 원문 그대로 report DTO에 실리기 때문에
 * language=en이어도 한국어가 그대로 남아 있던 간극이 있었다 — 이걸 메운다.
 *
 * medivice.translate.api-key가 없으면(DEEPL_API_KEY 미설정) 원문을 그대로 돌려준다 —
 * 번역이 실패하거나 키가 없다고 해서 보고서 생성 자체가 막히면 안 되기 때문이다
 * (AiClient의 mock 폴백과 같은 "정직하게 실패" 원칙).
 */
@Service
public class TranslationService {

    private static final String ENDPOINT = "https://api-free.deepl.com/v2/translate";

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String apiKey;

    public TranslationService(@Value("${medivice.translate.api-key:}") String apiKey) {
        this.apiKey = apiKey;
    }

    public boolean isEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * 여러 문장을 한 번의 호출로 번역한다(DeepL은 text 파라미터를 여러 번 실으면 순서를
     * 유지한 채 한 번에 번역해 준다) — 증상 기록이 여러 건이어도 API 호출은 1회로 끝낸다.
     * 실패하면(키 없음·네트워크 오류·DeepL 오류 응답) 원문 리스트를 그대로 돌려준다.
     */
    public List<String> translateAll(List<String> texts, String targetLang) {
        if (!isEnabled() || texts.isEmpty()) {
            return texts;
        }
        try {
            StringBuilder body = new StringBuilder("target_lang=").append(targetLang.toUpperCase());
            for (String text : texts) {
                body.append("&text=").append(URLEncoder.encode(text == null ? "" : text, StandardCharsets.UTF_8));
            }
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT))
                    .header("Authorization", "DeepL-Auth-Key " + apiKey)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(BodyPublishers.ofString(body.toString()))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return texts;
            }
            JsonNode translations = objectMapper.readTree(response.body()).get("translations");
            List<String> result = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) {
                result.add(translations != null && i < translations.size()
                        ? translations.get(i).get("text").asText()
                        : texts.get(i));
            }
            return result;
        } catch (IOException e) {
            return texts;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return texts;
        }
    }
}
