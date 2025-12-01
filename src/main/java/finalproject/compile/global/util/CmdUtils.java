package finalproject.compile.global.util;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.concurrent.TimeUnit;

/**
 * OS 명령어(javac/java)를 실행하는 유틸 클래스
 * - Windows / Linux 모두 호환
 * - 타임아웃 적용하여 무한 대기 방지
 * - 글자 수 제한(MAX_OUTPUT_CHARS)으로 메모리 폭주 방지
 * - 표준 출력 + 표준 에러를 단일 스트림으로 통합
 */
@Slf4j
public class CmdUtils {

    // 출력 글자 수 제한
    private static final int MAX_OUTPUT_CHARS = 10000;

    /**
     * 외부 명령어(javac/java) 실행 메서드
     */
    public static ExecutionResult runCommand(String command, long timeoutMillis) {

        StringBuilder output = new StringBuilder();  // 명령어 실행 출력 모음
        int exitCode = -1;                           // 프로세스 종료 코드
        Process process = null;                      // 외부 프로세스 핸들

        try {
            // --------------------------------------------------------
            // 현재 OS 확인 후 맞는 명령어 방식 선택
            // --------------------------------------------------------
            boolean isWindows = System.getProperty("os.name")
                    .toLowerCase()
                    .startsWith("windows");

            ProcessBuilder pb;
            if (isWindows) {
                pb = new ProcessBuilder("cmd.exe", "/c", command);
            } else {
                pb = new ProcessBuilder("sh", "-c", command);
            }

            pb.redirectErrorStream(true); // 표준 에러 통합
            process = pb.start();         // 프로세스 실행

            // 타임아웃 적용하여 무한루프 방지
            boolean finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);

            if (!finished) {
                process.destroyForcibly();
                return new ExecutionResult(
                        false,
                        "TIMEOUT: 실행 시간이 초과되었습니다. (5초)",
                        -1
                );
            }
            // 출력 스트림 읽기 (버퍼 방식 - 글자 수 제한)
            String charsetName = isWindows ? "MS949" : "UTF-8";

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), Charset.forName(charsetName)))) {

                char[] buffer = new char[1024]; // 1KB 버퍼
                int readCount;
                int totalChars = 0;

                // read(buffer)는 읽은 글자 수를 반환함 (-1이면 끝)
                while ((readCount = reader.read(buffer)) != -1) {

                    // 읽은 만큼 StringBuilder에 추가
                    output.append(buffer, 0, readCount);
                    totalChars += readCount;

                    //  글자 수 제한 초과 시 강제 중단
                    if (totalChars > MAX_OUTPUT_CHARS) {
                        output.append("\n... (출력 용량이 너무 커서 중단되었습니다) ...");

                        // 프로세스가 아직 살아있다면 확실히 종료
                        if (process.isAlive()) process.destroyForcibly();
                        break;
                    }
                }
            }

            // --------------------------------------------------------
            //  프로세스 종료 코드 (0 = 성공, 그 외 = 실패)
            // --------------------------------------------------------
            exitCode = process.exitValue();
            return new ExecutionResult(exitCode == 0, output.toString(), exitCode);

        } catch (Exception e) {
            log.error("Cmd Execution Error: {}", e.getMessage());
            return new ExecutionResult(false, "System Error: " + e.getMessage(), -1);

        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    public record ExecutionResult(boolean success, String output, int exitCode) { }
}