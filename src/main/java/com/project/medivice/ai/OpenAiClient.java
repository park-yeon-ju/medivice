package com.project.medivice.ai;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ReasoningEffort;
import com.project.medivice.config.MediviceProperties;
import com.openai.models.chat.completions.ChatCompletionContentPart;
import com.openai.models.chat.completions.ChatCompletionContentPartImage;
import com.openai.models.chat.completions.ChatCompletionContentPartText;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.StructuredChatCompletion;
import com.openai.models.chat.completions.StructuredChatCompletionCreateParams;
import com.openai.models.chat.completions.StructuredChatCompletionMessage;
import java.util.Base64;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * EXT-1(사진 → 구조화 JSON)·EXT-3(보고서 요약)을 OpenAI Chat Completions API로 처리한다.
 * OPENAI_API_KEY 환경변수가 필요하다(OpenAIOkHttpClient.fromEnv()가 읽는다) — 이 클래스는
 * medivice.ai.provider=openai일 때만 빈으로 등록되므로, 키가 없으면 provider를 mock으로 두면 된다.
 */
@Component
@ConditionalOnProperty(prefix = "medivice.ai", name = "provider", havingValue = "openai")
public class OpenAiClient implements AiClient {

    /** 구조화 출력의 루트 타입은 하나여야 해서, 약 여러 개를 배열로 감싼다. */
    public record OcrExtractionBatch(List<OcrExtractionResult> medications) {
    }

    private static final String OCR_PROMPT = """
            첨부한 사진은 처방전, 약봉투, 또는 영양제·상비약의 제품 라벨입니다.
            봉투 하나에 서로 다른 약이 여러 개 들어 있는 경우가 흔합니다(예: 아세클로페낙정,
            모사프리드정, 레바미피드정을 각각 담은 봉지가 한 봉투에 같이 옴). medications 배열에
            사진에서 실제로 구분되는 약마다 한 항목씩 넣으세요 — 약이 하나뿐이면 배열 길이는 1입니다.
            서로 다른 약을 하나로 합치지 마세요. 반대로, 알약 한 알에 원래 성분이 여러 개 든
            진짜 복합제(예: 아모잘탄정 = 암로디핀 + 로사르탄칼륨)는 그 약 하나의 ingredients
            배열에 성분을 전부 나열하되, medications 배열에서는 여전히 한 항목입니다.

            정확성이 무엇보다 중요합니다. 절대 지어내지 마세요:
            - 약 이름(productName)은 사진에서 실제로 읽은 글자만 쓰세요. 비슷하게 생긴 실제
              존재하는 약 이름으로 "그럴듯하게" 바꿔치기하지 마세요(예: "케이캡정"을 "케이프론정"으로
              추측하는 식). 글자가 흐려 일부만 보이면 보이는 부분만 쓰고, 아예 안 보이면 null로
              두세요 — 틀린 이름보다 빈 값이 낫습니다.
            - 성분명·함량도 마찬가지입니다. 정확히 안 보이면 null로 두고 confidence를 낮게 매기세요.
            각 항목의 confidence는 0.0(전혀 확신 없음)~1.0(매우 확실) 사이로 매기세요.

            【성분·함량표(예: "성분명 ········· 40mg" 처럼 점선·콜론으로 값이 붙는 표)를 읽을 때】
            이 표가 가장 실수가 많이 나는 부분입니다. 반드시 아래 순서로 확인하세요:
            1. 먼저 표에 실제로 몇 줄(성분 몇 개)이 있는지 세어보세요.
            2. 한 줄씩, 그 줄의 성분명과 그 줄의 점선·콜론 바로 뒤에 붙은 함량을 같은 줄에서만
               짝지으세요. 절대 다른 줄의 성분명과 함량을 섞지 마세요(예: 3번째 줄 이름에
               5번째 줄 함량을 붙이는 식의 착오가 실제로 자주 발생합니다).
            3. 최종 ingredients 배열의 항목 수는 1번에서 센 줄 수와 반드시 같아야 합니다.
               줄 하나를 통째로 건너뛰지 마세요 — 다 못 읽겠으면 그 줄만 name을 null로 두고
               자리는 남겨서, 최소한 "몇 개 성분이 있었는지"는 보존하세요.
            4. 사진이 90도 돌아가 있거나(세로 글자가 가로로 누움) 흔들렸어도, 그 상태 그대로
               글자를 읽으려고 시도하세요 — 회전 때문에 항목을 빠뜨리면 안 됩니다.
            5. 이름을 확신할 수 없는 성분은 실존하는 다른 성분명으로 대체하지 말고, 사진에 보이는
               글자를 최대한 그대로 옮기거나(예: 일부 글자가 흐리면 "○○제"처럼 보이는 부분만),
               정 안 되면 null로 두세요. 없는 성분명을 만들어내는 것이 이 서비스에서 가장 위험한
               실수입니다.

            hospitalName/department: 처방전일 때만 채웁니다(병원명, 진료과 대분류). 약국 이름은
            hospitalName에 넣지 마세요(조제 약국일 뿐 처방한 병원이 아닙니다) — 알 수 없으면 null.
            productName: 그 약의 제품명 전체(용량 포함, 예: "아모잘탄정 5/50mg").
            ingredients: 그 약 하나에 실제로 든 성분명(한글)·영문명(모르면 null)·1회 복용량당
            함량(숫자)·단위(mg/mcg/IU/mL 등)의 배열.
            dosePerIntake/doseUnit/timesPerDay: 그 약의 1회 투여량과 그 단위(정/캡슐/포 등), 1일 횟수.
            durationNote: "30일분"처럼 처방 일수가 적혀 있으면 그대로.
            suggestedType: 처방전·약봉투로 보이면 "PRESCRIPTION", 일반의약품이면 "OTC",
            건강기능식품이면 "SUPPLEMENT" 중 하나로 추정.
            note: 그 약 판독에 참고할 특이사항(예: "이름 일부가 잘려 확신할 수 없습니다"). 없으면 null.
            """;

    private final OpenAIClient client;
    private final String model;
    private final ReasoningEffort reasoningEffort;

    // medivice.ai.model·reasoning-effort는 application.properties로 뺐다 — 모델·튜닝값을
    // 바꾸려고 재컴파일할 필요가 없게 하기 위해서다(AI-Ready 4대 원칙의 "Model Parameter를
    // 코드와 분리" 요구사항, TROUBLESHOOTING.md #24 이어지는 지적).
    public OpenAiClient(MediviceProperties properties) {
        this.client = OpenAIOkHttpClient.fromEnv();
        this.model = properties.ai().model();
        this.reasoningEffort = ReasoningEffort.of(properties.ai().reasoningEffort());
    }

    @Override
    public List<OcrExtractionResult> extractMedicationInfo(byte[] imageBytes, String mimeType) {
        String base64 = Base64.getEncoder().encodeToString(imageBytes);
        String dataUrl = "data:" + normalizeMimeType(mimeType) + ";base64," + base64;

        ChatCompletionContentPartImage image = ChatCompletionContentPartImage.builder()
                .imageUrl(ChatCompletionContentPartImage.ImageUrl.builder()
                        .url(dataUrl)
                        .detail(ChatCompletionContentPartImage.ImageUrl.Detail.HIGH)
                        .build())
                .build();
        ChatCompletionContentPartText text = ChatCompletionContentPartText.builder()
                .text(OCR_PROMPT)
                .build();

        StructuredChatCompletionCreateParams<OcrExtractionBatch> params = ChatCompletionCreateParams.builder()
                .model(model)
                // reasoning effort는 기본값 low(설정으로 뺌, medivice.ai.reasoning-effort).
                // 성분·함량표처럼 촘촘한 표를 줄 단위로 정확히 짝지어야 해서 원래는 XHIGH까지
                // 올렸었다(성분 누락·줄 뒤섞임이 관찰되어 추가함). 그런데 실사진(복약안내지, 7개 약)
                // 벤치마크로 none/low/medium/high/xhigh를 다 재보니 응답시간은
                // low 17.4s < medium 30.9s < high 54.5s < xhigh 468.7s 로 폭발적으로 늘어나는데,
                // 약 이름 7개 정확도는 low·medium·xhigh가 전부 7/7으로 동일했고 오히려
                // high·none에서만 글자 하나가 틀렸다(비졸본정→비출본정) — reasoning 강도와
                // 정확도가 비례하지 않았다. TROUBLESHOOTING.md #24 참고. 성분·함량표가 촘촘한
                // 사진에서 다시 누락·뒤섞임이 보이면 재컴파일 없이 설정값만 medium/high로 올린다
                // (정확도가 같았던 medium을 우선 시도).
                .reasoningEffort(reasoningEffort)
                .responseFormat(OcrExtractionBatch.class)
                .addUserMessageOfArrayOfContentParts(List.of(
                        ChatCompletionContentPart.ofText(text),
                        ChatCompletionContentPart.ofImageUrl(image)))
                .build();

        StructuredChatCompletion<OcrExtractionBatch> response = client.chat().completions().create(params);
        OcrExtractionBatch batch = firstStructuredResult(response);
        return batch.medications() != null ? batch.medications() : List.of();
    }

    @Override
    public String summarizeReport(ReportContext c) {
        boolean english = "en".equalsIgnoreCase(c.language());
        String prompt = (english
                ? "Write a 2-3 sentence mechanical summary (not a diagnosis) of this period's rule-engine "
                        + "results. Do not invent facts beyond these numbers: medications=%d, cautions=%d, "
                        + "high-priority issues=%d, symptom entries=%d."
                : "이 기간의 규칙 기반 점검 결과를 2~3문장으로 기계적으로 요약하세요(진단이 아님). "
                        + "다음 숫자 밖의 사실은 지어내지 마세요: 복용 항목 %d건, 주의 %d건, 높은 주의 %d건, "
                        + "증상 기록 %d건.")
                .formatted(c.medicationCount(), c.warnCount(), c.critCount(), c.symptomCount());

        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model(model)
                .addUserMessage(prompt)
                .build();

        var response = client.chat().completions().create(params);
        return response.choices().stream()
                .findFirst()
                .flatMap(choice -> choice.message().content())
                .orElse(english ? "No summary was returned." : "요약을 받지 못했습니다.");
    }

    @Override
    public String explainMedication(MedicationExplainContext c) {
        String ingredients = String.join(", ", c.ingredientNames());
        boolean hasSideEffect = c.sideEffect() != null && !c.sideEffect().isBlank();

        // 부작용 원문이 있을 때만 "졸릴 수 있다" 같은 체감 변화를 물어본다 — 없으면 아예
        // 언급하지 말라고 못박아서, 모델이 성분 지식만으로 부작용을 지어내는 걸 막는다.
        String sideEffectInstruction = hasSideEffect
                ? """

                다음은 이 약의 식약처 공식 부작용 안내 원문입니다:
                "%s"
                이 원문 안에서 환자가 복용 중 실제로 느낄 수 있는(예: 졸림, 어지러움, 속쓰림처럼
                체감되는) 대표 증상이 있으면 딱 하나만 골라 자연스럽게 문장에 포함하세요. 이 원문에
                없는 부작용은 추가하지 마세요. 원문에 체감 증상이 없거나 애매하면 부작용은 언급하지
                말고 넘어가세요.
                """.formatted(c.sideEffect())
                : "\n부작용 정보가 주어지지 않았으니, 부작용을 추측해서 언급하지 마세요.";

        String formatInstruction = """
                - 진단이 아니라 설명입니다. 마지막에 "이상 증상이 지속되면 의료진에게 알리세요" 같은
                  안내를 짧게 덧붙이세요.
                - 화면에 그대로 표시되는 순수 텍스트입니다. 마크다운(**굵게**, 목록, 제목 등)을
                  쓰지 말고 평문으로만 쓰세요.
                - 전체 1~3문장으로 짧게 쓰세요.
                """;

        String prompt;
        if (c.efficacy() != null && !c.efficacy().isBlank()) {
            // efficacy는 식약처 e약은요 원문 — 지어내지 말고 이 문장을 그대로 쉬운 말로 다듬기만 하라고 못박는다.
            prompt = """
                    다음은 의약품 "%s"의 식약처 공식 효능·효과 설명입니다:
                    "%s"

                    이 내용만 근거로, 환자가 이해하기 쉬운 한국어로 바꿔 쓰세요.
                    - 이 문장 밖의 사실을 지어내지 마세요(용법·용량 등을 새로 추가하지 마세요).
                    %s
                    %s
                    """.formatted(c.productName(), c.efficacy(), sideEffectInstruction, formatInstruction);
        } else {
            prompt = """
                    의약품 "%s"에 실제로 든 성분은 %s입니다.
                    이 성분들이 일반적으로 어떤 목적으로 쓰이는지, 환자가 이해하기 쉬운 한국어로
                    설명하세요.
                    - 이 약이 처방된 이유를 추측하거나 용법·용량을 새로 만들어내지 마세요 — 성분의
                      일반적인 약리 분류(예: "혈압 관리에 쓰이는 성분")만 언급하세요.
                    %s
                    %s
                    """.formatted(c.productName(), ingredients, sideEffectInstruction, formatInstruction);
        }

        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model(model)
                .addUserMessage(prompt)
                .build();

        var response = client.chat().completions().create(params);
        return response.choices().stream()
                .findFirst()
                .flatMap(choice -> choice.message().content())
                .orElse(null);
    }

    private static <T> T firstStructuredResult(StructuredChatCompletion<T> response) {
        StructuredChatCompletionMessage<T> message = response.choices().stream()
                .findFirst()
                .map(StructuredChatCompletion.Choice::message)
                .orElseThrow(() -> new IllegalStateException("OCR 결과를 받지 못했습니다."));
        if (message.refusal().isPresent()) {
            throw new IllegalStateException("이미지를 분석할 수 없습니다: " + message.refusal().get());
        }
        return message.content().orElseThrow(() -> new IllegalStateException("OCR 결과를 받지 못했습니다."));
    }

    private static String normalizeMimeType(String mimeType) {
        if (mimeType == null) {
            throw new IllegalArgumentException("이미지 형식을 확인할 수 없습니다.");
        }
        return switch (mimeType.toLowerCase()) {
            case "image/jpeg", "image/jpg" -> "image/jpeg";
            case "image/png" -> "image/png";
            case "image/webp" -> "image/webp";
            case "image/gif" -> "image/gif";
            default -> throw new IllegalArgumentException(
                    "지원하지 않는 이미지 형식입니다: " + mimeType + " (JPG · PNG · WEBP만 가능, HEIC는 변환 후 시도해주세요)");
        };
    }
}
