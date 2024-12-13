package io.github.ollama4j.models.response;

import static io.github.ollama4j.utils.Utils.getObjectMapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.Getter;
import lombok.val;

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
   *  Get the response class of the structured response
   *
   * @return String completion/response text
   */
  private Class<?> responseType;

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

  public OllamaResult(String response, Class<?> responseType, long responseTime, int httpStatusCode) {
    this.response = response;
    this.responseType = responseType;
    this.responseTime = responseTime;
    this.httpStatusCode = httpStatusCode;
  }

  public <T> T getStructuredResponse() {
    if (this.responseType == null) {
      throw new IllegalStateException("Response class was not set in the original request; response cannot be structured.");
    }

    if (this.response == null) {
      throw new IllegalStateException("Response is null; cannot structure a response.");
    }

    try {
      @SuppressWarnings("unchecked")
      T deserializedValue = (T) getObjectMapper().readValue(response, responseType);
      return deserializedValue;
    } catch (JsonProcessingException e) {
      throw new RuntimeException("Failed to parse response into type: " + responseType.getName(), e);
    }
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
