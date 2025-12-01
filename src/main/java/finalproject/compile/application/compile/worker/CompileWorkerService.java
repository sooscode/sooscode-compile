package finalproject.compile.application.compile.worker;

import finalproject.compile.domain.compile.entity.CompileJob;
import finalproject.compile.domain.compile.service.CompileJobService;
import finalproject.compile.global.util.CmdUtils;
import finalproject.compile.infra.file.FileUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class CompileWorkerService {

    private final FileUtil fileUtil;
    private final CompileJobService jobService;

    // 배포 환경에서 사용할 격리된 도커 이미지
    private static final String DOCKER_IMAGE = "openjdk:17-alpine";

    private static final String[] BLACKLIST = {
            "System.exit",
            "Runtime.getRuntime",
            "ProcessBuilder",
            "java.io.File",
            "java.nio.file",
            "java.net",
            "java.lang.reflect",
            "sun.misc.Unsafe",
            "Thread",
            "ForkJoinPool"
    };

    /**
     * 실제 컴파일 + 실행을 수행하는 핵심 메서드 (EC2 Docker 환경)
     * 1) Main.java 생성
     * 2) Docker 컨테이너 띄워서 javac 실행
     * 3) Docker 컨테이너 띄워서 java 실행 (샌드박스 격리)
     * 4) 성공/실패 여부 저장
     * 5) 작업 폴더 정리
     */
    public void execute(CompileJob job) {
        try {
            //  Worker 어떤 jobId를 처리 로그
            log.info("[Worker] 컴파일 시작 jobId={}", job.getJobId());

            validateCode(job.getCode());

            //  컴파일에 사용할 파일 이름 지정
            String fileName = "Main.java";

            //  /tmp/compiler/{jobId}/Main.java 파일 생성
            //    - 디렉토리 생성
            //    - 사용자가 보낸 코드 내용 기록
            fileUtil.createaJavaFile(job.getJobId(), fileName, job.getCode());

            //  호스트(EC2)의 파일 경로 설정
            //    - Docker Volume Mount를 위해 호스트 기준 경로 필요
            String hostPath = fileUtil.getBasePath() + "/" + job.getJobId();

            //  도커 기반 javac 컴파일 명령어 구성
            //     --rm: 실행 후 컨테이너 삭제
            //      --v: 호스트 경로를 컨테이너 내부 /app 에 마운트
            //      --w: 작업 디렉토리 설정
            String compileCmd = String.format(
                    "docker run --rm " +
                            "-v %s:/app " +
                            "-w /app " +
                            "%s " +
                            "javac Main.java",
                    hostPath, DOCKER_IMAGE
            );

            //  compileCmd 명령어 실행 (timeout 10000)
            //    - 도커 컨테이너 실행 시간을 고려하여 타임아웃 여유 있게 설정
            CmdUtils.ExecutionResult compileResult = CmdUtils.runCommand(compileCmd, 10000);

            //  컴파일 실패 시 → 즉시 종료
            //    - compileResult.success() == false → 문법 오류
            if (!compileResult.success()) {
                // 실행 결과 를 job에 기록
                jobService.completeJob(job.getJobId(), false, compileResult.output());
                return;
            }

            //  도커 기반 java 실행 명령어 구성
            //     --network none: 인터넷 차단
            //     --memory 128m: 메모리 제한
            //     --cpus 0.5: CPU 사용량 제한
            String runCmd = String.format(
                    "docker run --rm " +
                            "--network none " +
                            "--memory 128m " +
                            "--memory-swap 128m " +
                            "--cpus 0.5 " +
                            "-v %s:/app " +
                            "-w /app " +
                            "%s " +
                            "java Main",
                    hostPath, DOCKER_IMAGE
            );

            //  java 실행 (timeout 5000)
            CmdUtils.ExecutionResult runResult = CmdUtils.runCommand(runCmd, 5000);

            //  실행 성공 여부 + 출력 내용 저장
            jobService.completeJob(job.getJobId(), runResult.success(), runResult.output());

        } catch (SecurityException e) {
            jobService.completeJob(job.getJobId(), false, "Security Error: " + e.getMessage());
        } catch (Exception e) {
            jobService.completeJob(job.getJobId(), false, e.getMessage());
        } finally {
            //  컴파일/실행 끝난 뒤 해당 jobId 폴더 제거
            //  (작업 디렉토리 정리)
            fileUtil.deleteFolder(job.getJobId());
        }
    }
    private void validateCode(String code) {
        for (String keyword : BLACKLIST) {
            if (code.contains(keyword)) {
                throw new SecurityException("허용되지 않는 키워드가 포함되어 있습니다: " + keyword);
            }
        }
    }
}