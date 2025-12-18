package finalproject.compile.infra.file;

import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * 컴파일 작업에 필요한 파일 처리 유틸 클래스
 * - /tmp/compiler/{jobId}/Main.java 파일 생성
 * - 작업 종료 후 jobId 폴더 삭제
 * - Worker 서버에서만 사용됨
 */
@Component
public class FileUtil {
    /**
     *  window , linux 둘다 돌아가야함 로컬테스트랑 ec2 배포생각
     * */
    private static final String BASE_PATH;
    static {
        boolean isWindows = System.getProperty("os.name").toLowerCase().startsWith("windows");

        if (isWindows) {
            // Windows용 로컬 테스트 경로
            BASE_PATH = System.getProperty("user.dir") + "\\compiler_jobs";
        } else {
            // Linux(EC2/Docker)용 배포 경로
            BASE_PATH = "/tmp/compiler";
        }
    }
    public String getBasePath() {
        return BASE_PATH;
    }

    /**
     * Java 파일 생성 메서드
     * 1) /tmp/compiler/{jobId}/ 디렉토리 생성
     * 2) 해당 디렉토리에 fileName(Main.java) 생성
     * 3) 사용자가 제출한 code 내용을 파일에 기록
     */
    public void createJavaFile(String jobId, String fileName, String code) throws IOException {

        // jobId 전용 디렉토리 경로 생성
        File dir = new File(BASE_PATH + "/" + jobId);

        // 디렉토리가 없다면 새롭게 생성
        if(!dir.exists()){
            dir.mkdirs(); // /tmp/compiler/{jobId}/
        }
        dir.setReadable(true, false);
        dir.setWritable(true, false);
        dir.setExecutable(true, false);

        // 위에서 만든 디렉토리 내부에 fileName(Main.java) 생성
        File file = new File(dir, fileName);

        // 파일에 코드 내용 기록
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8)) {

            writer.write(code);
        }

        // 사용자가 요청에서 전달한 코드 그대로 기록
    }

    /**
     * Job 작업 폴더 삭제 메서드
     * 1) /tmp/compiler/{jobId}/ 내부 파일 제거
     * 2) 마지막으로 jobId 폴더 자체 삭제
     * - 컴파일/실행 끝난 뒤 정리 용도
     */
    public void deleteFolder(String jobId){

        // 삭제할 jobId 폴더 기준 경로
        File dir = new File(BASE_PATH + "/" + jobId);

        // 폴더 자체가 없으면 더 이상 할 일 없음
        if(!dir.exists()) {
            return;
        }

        // jobId 폴더 내부 파일 목록 가져오기
        File[] files = dir.listFiles();

        // 내부 파일이 존재하면 하나씩 삭제
        if(files != null){
            for(File file : files){
                file.delete();
            }
        }
        // 모든 파일 정리 후 해당 jobId 폴더 자체 삭제
        dir.delete();
    }
}