package finalproject.compile.application.compile.controller;

import finalproject.compile.application.compile.dto.CompileRequest;
import finalproject.compile.application.compile.dto.CompileResponse;
import finalproject.compile.application.compile.dto.CompileResultResponse;
import finalproject.compile.application.compile.service.CompileApiService;
import finalproject.compile.application.compile.service.CompileResultService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;


@RestController
@RequiredArgsConstructor
@RequestMapping("/api/compile")
public class CompileController {
    private final CompileApiService apiService;
    private final CompileResultService resultService;

    /**
     * 코드 실행 요청 API
     */
    @PostMapping("/run")
    public ResponseEntity<CompileResponse> run(@Valid @RequestBody CompileRequest request) {
        return ResponseEntity.ok(apiService.runCode(request));
    }
    /** 결과 조회 API */
    @GetMapping("/result/{jobId}")
    public ResponseEntity<CompileResultResponse> result(@PathVariable String jobId) {
        return ResponseEntity.ok(resultService.readResult(jobId));
    }
}