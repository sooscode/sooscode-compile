package finalproject.compile.application.compile.worker;

import finalproject.compile.domain.compile.entity.CompileJob;
import finalproject.compile.domain.compile.service.CompileJobService;
import finalproject.compile.global.util.CmdUtils;
import finalproject.compile.infra.client.CallbackClient;
import finalproject.compile.infra.file.FileUtil;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 컴파일 워커 서비스 클래스.
 * <p>
 * Docker 컨테이너를 관리하고, 실제 소스 코드를 컴파일 및 실행하는 핵심 로직을 담당
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompileWorkerService {

    private final FileUtil fileUtil;
    private final CompileJobService jobService;
    private final CallbackClient callbackClient;

    // 생성할 컨테이너 이름 접두사
    private static final String CONTAINER_PREFIX = "compile-executor-";

    // 사용할 Docker 이미지 (Java 17)
    private static final String DOCKER_IMAGE = "eclipse-temurin:17-jdk";

    // 워커 개수
    @Value("${compile.worker.count:2}")
    private int workerCount;

    // 컨테이너 최대 사용 횟수
    @Value("${compile.container.max-usage:100}")
    private int maxContainerUsage;

    // 각 컨테이너 사용 횟수 추적
    private AtomicInteger[] usageCounts;

    // 보안상 허용하지 않는 키워드 목록
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
     * 서버 시작 시 호출되어 컨테이너 풀 초기화
     */
    @PostConstruct
    public void initContainers() {
        log.info("Initializing Container Pool. Count: {}, MaxUsage: {}", workerCount, maxContainerUsage);

        usageCounts = new AtomicInteger[workerCount];
        for (int i = 0; i < workerCount; i++) {
            usageCounts[i] = new AtomicInteger(0);
            createContainer(i);
        }
    }

    /**
     * 지정된 워커 ID에 대응하는 Docker 컨테이너 생성
     */
    private void createContainer(int workerId) {
        String containerName = CONTAINER_PREFIX + workerId;
        try {
            // 기존 컨테이너 제거
            CmdUtils.runCommand("docker rm -f " + containerName, 5000);

            // 보안 및 리소스 제한 옵션을 적용하여 컨테이너 실행
            String runCmd = String.format(
                    "docker run -d --name %s " +
                            "--network none " +
                            "--pids-limit 100 " +
                            "--cap-drop ALL " +
                            "--memory 512m " +
                            "--cpus 0.8 " +
                            "-v %s:/app " +
                            "%s tail -f /dev/null",
                    containerName, fileUtil.getBasePath(), DOCKER_IMAGE
            );

            CmdUtils.ExecutionResult result = CmdUtils.runCommand(runCmd, 10000);
            if (result.success()) {
                log.info("Container [{}] created/reset successfully.", containerName);
                usageCounts[workerId].set(0);
            } else {
                throw new RuntimeException("Container Init Failed: " + result.output());
            }
        } catch (Exception e) {
            log.error("Critical error while creating container [{}]", containerName, e);
            throw new RuntimeException(e);
        }
    }

    /**
     * 서버 종료 시 컨테이너 정리
     */
    @PreDestroy
    public void cleanupContainers() {
        for (int i = 0; i < workerCount; i++) {
            CmdUtils.runCommand("docker rm -f " + CONTAINER_PREFIX + i, 5000);
        }
        log.info("All containers cleaned up.");
    }

    /**
     * 컴파일 및 실행 요청 처리
     *
     * @param job      컴파일 작업 정보
     * @param workerId 할당된 워커 ID
     */
    public void execute(CompileJob job, int workerId) {
        String containerName = CONTAINER_PREFIX + workerId;
        int maxRetries = 1;

        try {
            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                try {
                    // 컨테이너 사용 횟수 초과 시 재생성
                    if (usageCounts[workerId].get() >= maxContainerUsage) {
                        log.info("Container [{}] usage limit exceeded. Recreating...", containerName);
                        createContainer(workerId);
                    }

                    // 핵심 컴파일/실행 로직 수행
                    runCompileAndExecution(job, containerName);
                    return;

                } catch (SecurityException se) {
                    // 보안 위반은 사용자 책임
                    handleResult(job, false, "Security Error: " + se.getMessage());
                    return;

                } catch (IllegalArgumentException ie) {
                    // main 없음, 다중 main 등 유효성 오류
                    handleResult(job, false, "Compile Error: " + ie.getMessage());
                    return;

                } catch (Exception e) {
                    // 시스템 오류 발생 시 컨테이너 재생성 후 재시도
                    if (attempt == maxRetries) {
                        throw new RuntimeException("Max retries exceeded", e);
                    }
                    createContainer(workerId);
                } finally {
                    usageCounts[workerId].incrementAndGet();
                }
            }
        } finally {
            // 작업 종료 후 임시 파일 정리
            fileUtil.deleteFolder(job.getJobId());
        }
    }

    /**
     * 파일 생성 → 컴파일 → 실행을 순차적으로 수행
     */
    private void runCompileAndExecution(CompileJob job, String containerName) {
        log.info("Executing JobId={}", job.getJobId());

        //  코드 보안 검사
        validateCode(job.getCode());

        //  main 메서드를 실제로 소유한 클래스 탐색
        String entryClassName = detectEntryClass(job.getCode());

        try {
            //  탐색된 클래스 이름으로 파일 생성
            //    public class Solution -> Solution.java
            fileUtil.createJavaFile(job.getJobId(), entryClassName + ".java", job.getCode());
        } catch (IOException e) {
            throw new RuntimeException("File Creation Failed", e);
        }

        //  컴파일 (단일 파일 명시)
        String compileCmd = String.format(
                "docker exec -w /app/%s %s javac -encoding UTF-8 %s.java",
                job.getJobId(), containerName, entryClassName
        );
        CmdUtils.ExecutionResult compileResult = CmdUtils.runCommand(compileCmd, 10000);

        if (!compileResult.success()) {
            handleResult(job, false, compileResult.output());
            return;
        }

        //  실행
        String runCmd = String.format(
                "docker exec -w /app/%s %s java -Dfile.encoding=UTF-8 %s",
                job.getJobId(), containerName, entryClassName
        );
        CmdUtils.ExecutionResult runResult = CmdUtils.runCommand(runCmd, 5000);

        handleResult(job, runResult.success(), runResult.output());
    }

    /**
     * 코드에서 public static void main을 포함하는 실제 클래스 이름 탐색
     */
    private String detectEntryClass(String code) {
        // main 메서드 개수 검증
        Matcher mainMatcher = Pattern.compile("public\\s+static\\s+void\\s+main\\s*\\(").matcher(code);
        int count = 0;
        while (mainMatcher.find()) count++;

        if (count == 0) {
            throw new IllegalArgumentException("실행할 main 메서드를 찾을 수 없습니다.");
        }
        if (count > 1) {
            throw new IllegalArgumentException("main 메서드는 하나만 존재해야 합니다.");
        }

        // main 위치 이전에서 가장 마지막 class 선언을 탐색
        Matcher mainPos = Pattern.compile("public\\s+static\\s+void\\s+main\\s*\\(").matcher(code);
        if (mainPos.find()) {
            String beforeMain = code.substring(0, mainPos.start());
            Matcher classMatcher = Pattern.compile("(public\\s+)?class\\s+(\\w+)").matcher(beforeMain);

            String className = null;
            while (classMatcher.find()) {
                className = classMatcher.group(2);
            }

            if (className != null) {
                return className;
            }
        }

        throw new IllegalArgumentException("main 메서드를 포함하는 클래스를 찾을 수 없습니다.");
    }

    /**
     * 컴파일/실행 결과 처리 및 콜백
     */
    private void handleResult(CompileJob job, boolean success, String output) {
        job.complete(success, output);
        jobService.completeJob(job.getJobId(), success, output);
        callbackClient.sendResultCallback(job);
    }

    /**
     * 코드 내 금지 키워드 검사
     */
    private void validateCode(String code) {
        for (String keyword : BLACKLIST) {
            if (code.contains(keyword)) {
                throw new SecurityException("Forbidden keyword detected: " + keyword);
            }
        }
    }

    /**
     * 현재 설정된 워커 개수 반환
     */
    public int getWorkerCount() {
        return workerCount;
    }
}
