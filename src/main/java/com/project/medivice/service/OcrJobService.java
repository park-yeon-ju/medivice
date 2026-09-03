package com.project.medivice.service;

import com.project.medivice.dto.OcrJobDto;
import com.project.medivice.exception.NotFoundException;
import com.project.medivice.service.OcrService.RawInput;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * UC8~10(EXT-1) 비동기 파이프라인. AI-Ready Web Service 원칙(강의 자료 4쪽) ③이 요구하는
 * "비동기 처리 + 상태 관리(Pending/Completed)를 수용할 수 있는 Endpoint 구조" 부분이다.
 *
 * 큐 서버 없이 작업 상태를 메모리에만 들고 있다 — 이 규모(로컬 1개 인스턴스, 3일짜리 과제
 * 범위)에서는 그걸로 충분하고, 재시작하면 진행 중이던 작업은 사라진다는 한계를 그대로
 * 받아들인다. TROUBLESHOOTING.md #22·#24 참고.
 */
@Service
public class OcrJobService {

    private final OcrService ocrService;
    private final Map<String, OcrJob> jobs = new ConcurrentHashMap<>();

    public OcrJobService(OcrService ocrService) {
        this.ocrService = ocrService;
    }

    /**
     * 파일 검증·바이트 추출만 요청 스레드에서 동기로 끝내고 바로 202를 낼 수 있게 jobId를
     * 돌려준다. 실제 AI 호출은 OcrService.processAsync가 별도 스레드에서 처리하고, 그 결과가
     * 오면(성공이든 실패든) jobs 맵을 갱신한다 — 컨트롤러는 이 완료를 기다리지 않는다.
     */
    public String submit(MultipartFile file) {
        RawInput input = ocrService.readAndValidate(file);
        String jobId = UUID.randomUUID().toString();
        jobs.put(jobId, new OcrJob(OcrJobStatus.PENDING, null, null));

        var future = ocrService.processAsync(input.bytes(), input.mimeType());
        jobs.put(jobId, new OcrJob(OcrJobStatus.PROCESSING, null, null));
        future.whenComplete((result, error) -> {
                    if (error != null) {
                        // CompletableFuture는 원인 예외를 CompletionException으로 한 번 감싸므로 벗겨낸다.
                        Throwable cause = error.getCause() != null ? error.getCause() : error;
                        jobs.put(jobId, new OcrJob(OcrJobStatus.FAILED, null, cause.getMessage()));
                    } else {
                        jobs.put(jobId, new OcrJob(OcrJobStatus.COMPLETED, result, null));
                    }
                });

        return jobId;
    }

    public OcrJobDto getJob(String jobId) {
        OcrJob job = jobs.get(jobId);
        if (job == null) {
            throw new NotFoundException("OCR 작업을 찾을 수 없습니다: " + jobId);
        }
        return new OcrJobDto(jobId, job.status().name(), job.result(), job.error());
    }
}
