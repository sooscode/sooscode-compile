package finalproject.compile.application.compile.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CompileRequest {

    @NotBlank(message = "코드는 비어있을 수 없습니다.")
    @Size(max = 10000, message = "코드 길이는 10,000자를 초과할 수 없습니다.")
    private String code;
}
