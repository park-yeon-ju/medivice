package com.project.medivice.service;

import com.project.medivice.dto.OcrResultDto;
import java.util.List;

/** OcrJobService가 메모리에 들고 있는 작업 1건의 상태. */
record OcrJob(OcrJobStatus status, List<OcrResultDto> result, String error) {
}
