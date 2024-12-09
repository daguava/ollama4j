package io.github.ollama4j.models.response;

import static io.github.ollama4j.utils.Utils.getObjectMapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.Data;
import lombok.Getter;

/** The type Ollama result. */
@Getter
@SuppressWarnings("unused")
@Data
public class OllamaResult {
  /**
   * -- GETTER --
   *  Get the completion/response text
   *
   * @return String completion/response text
   */
  protected final String response;

  /**
   * -- GETTER --
   *  Get the response status code.
   *
   * @return int - response status code
   */
  protected int httpStatusCode;

  /**
   * -- GETTER --
   *  Get the response time in milliseconds.
   *
   * @return long - response time in milliseconds
   */
  protected long responseTime = 0;

  public OllamaResult(String response, long responseTime, int httpStatusCode) {
    this.response = response;
    this.responseTime = responseTime;
    this.httpStatusCode = httpStatusCode;
  }

  @Override
  public String toString() {
    try {
      return getObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(this);
    } catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }
}
