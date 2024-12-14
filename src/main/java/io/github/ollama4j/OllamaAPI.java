package io.github.ollama4j;

import io.github.ollama4j.exceptions.OllamaBaseException;
import io.github.ollama4j.exceptions.RoleNotFoundException;
import io.github.ollama4j.exceptions.ToolInvocationException;
import io.github.ollama4j.exceptions.ToolNotFoundException;
import io.github.ollama4j.models.chat.*;
import io.github.ollama4j.models.embeddings.OllamaEmbedRequestModel;
import io.github.ollama4j.models.embeddings.OllamaEmbeddingResponseModel;
import io.github.ollama4j.models.embeddings.OllamaEmbeddingsRequestModel;
import io.github.ollama4j.models.embeddings.OllamaEmbedResponseModel;
import io.github.ollama4j.models.generate.OllamaGenerateRequest;
import io.github.ollama4j.models.generate.OllamaStreamHandler;
import io.github.ollama4j.models.ps.ModelsProcessResponse;
import io.github.ollama4j.models.request.*;
import io.github.ollama4j.models.response.*;
import io.github.ollama4j.tools.*;
import io.github.ollama4j.utils.Options;
import io.github.ollama4j.utils.Utils;
import lombok.Setter;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

import lombok.val;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 * The base Ollama API class.
 */
@SuppressWarnings({"DuplicatedCode", "resource"})
public class OllamaAPI {

    private static final Logger logger = LoggerFactory.getLogger(OllamaAPI.class);
    private final String host;
    /**
     * -- SETTER --
     * Set request timeout in seconds. Default is 3 seconds.
     */
    @Setter
    private long requestTimeoutSeconds = 10;
    /**
     * -- SETTER --
     * Set/unset logging of responses
     */
    @Setter
    private boolean verbose = true;
    private BasicAuth basicAuth;

    private final ToolRegistry toolRegistry = new ToolRegistry();

    /**
     * Instantiates the Ollama API with default Ollama host: <a href="http://localhost:11434">http://localhost:11434</a>
     **/
    public OllamaAPI() {
        this.host = "http://localhost:11434";
    }

    /**
     * Instantiates the Ollama API with specified Ollama host address.
     *
     * @param host the host address of Ollama server
     */
    public OllamaAPI(String host) {
        if (host.endsWith("/")) {
            this.host = host.substring(0, host.length() - 1);
        } else {
            this.host = host;
        }
    }

    /**
     * Set basic authentication for accessing Ollama server that's behind a reverse-proxy/gateway.
     *
     * @param username the username
     * @param password the password
     */
    public void setBasicAuth(String username, String password) {
        this.basicAuth = new BasicAuth(username, password);
    }

    /**
     * API to check the reachability of Ollama server.
     *
     * @return true if the server is reachable, false otherwise.
     */
    public boolean ping() {
        String url = this.host + "/api/tags";
        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest httpRequest = null;
        try {
            httpRequest = getRequestBuilderDefault(new URI(url)).header("Accept", "application/json").header("Content-type", "application/json").GET().build();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
        HttpResponse<String> response = null;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (HttpConnectTimeoutException e) {
            return false;
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
        int statusCode = response.statusCode();
        return statusCode == 200;
    }

    /**
     * Provides a list of running models and details about each model currently loaded into memory.
     *
     * @return ModelsProcessResponse containing details about the running models
     * @throws IOException          if an I/O error occurs during the HTTP request
     * @throws InterruptedException if the operation is interrupted
     * @throws OllamaBaseException  if the response indicates an error status
     */
    public ModelsProcessResponse ps() throws IOException, InterruptedException, OllamaBaseException {
        String url = this.host + "/api/ps";
        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest httpRequest = null;
        try {
            httpRequest = getRequestBuilderDefault(new URI(url)).header("Accept", "application/json").header("Content-type", "application/json").GET().build();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
        HttpResponse<String> response = null;
        response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        int statusCode = response.statusCode();
        String responseString = response.body();
        if (statusCode == 200) {
            return Utils.getObjectMapper().readValue(responseString, ModelsProcessResponse.class);
        } else {
            throw new OllamaBaseException(statusCode + " - " + responseString);
        }
    }

    /**
     * Lists available models from the Ollama server.
     *
     * @return a list of models available on the server
     * @throws OllamaBaseException  if the response indicates an error status
     * @throws IOException          if an I/O error occurs during the HTTP request
     * @throws InterruptedException if the operation is interrupted
     * @throws URISyntaxException   if the URI for the request is malformed
     */
    public List<Model> listModels() throws OllamaBaseException, IOException, InterruptedException, URISyntaxException {
        String url = this.host + "/api/tags";
        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest httpRequest = getRequestBuilderDefault(new URI(url)).header("Accept", "application/json").header("Content-type", "application/json").GET().build();
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        int statusCode = response.statusCode();
        String responseString = response.body();
        if (statusCode == 200) {
            return Utils.getObjectMapper().readValue(responseString, ListModelsResponse.class).getModels();
        } else {
            throw new OllamaBaseException(statusCode + " - " + responseString);
        }
    }

    /**
     * Retrieves a list of models from the Ollama library. This method fetches the available models directly from Ollama
     * library page, including model details such as the name, pull count, popular tags, tag count, and the time when model was updated.
     *
     * @return A list of {@link LibraryModel} objects representing the models available in the Ollama library.
     * @throws OllamaBaseException  If the HTTP request fails or the response is not successful (non-200 status code).
     * @throws IOException          If an I/O error occurs during the HTTP request or response processing.
     * @throws InterruptedException If the thread executing the request is interrupted.
     * @throws URISyntaxException   If there is an error creating the URI for the HTTP request.
     */
    public List<LibraryModel> listModelsFromLibrary() throws OllamaBaseException, IOException, InterruptedException, URISyntaxException {
        String url = "https://ollama.com/library";
        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest httpRequest = getRequestBuilderDefault(new URI(url)).header("Accept", "application/json").header("Content-type", "application/json").GET().build();
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        int statusCode = response.statusCode();
        String responseString = response.body();
        List<LibraryModel> models = new ArrayList<>();
        if (statusCode == 200) {
            Document doc = Jsoup.parse(responseString);
            Elements modelSections = doc.selectXpath("//*[@id='repo']/ul/li/a");
            for (Element e : modelSections) {
                LibraryModel model = new LibraryModel();
                Elements names = e.select("div > h2 > div > span");
                Elements desc = e.select("div > p");
                Elements pullCounts = e.select("div:nth-of-type(2) > p > span:first-of-type > span:first-of-type");
                Elements popularTags = e.select("div > div > span");
                Elements totalTags = e.select("div:nth-of-type(2) > p > span:nth-of-type(2) > span:first-of-type");
                Elements lastUpdatedTime = e.select("div:nth-of-type(2) > p > span:nth-of-type(3) > span:nth-of-type(2)");

                if (names.first() == null || names.isEmpty()) {
                    // if name cannot be extracted, skip.
                    continue;
                }
                Optional.ofNullable(names.first()).map(Element::text).ifPresent(model::setName);
                model.setDescription(Optional.ofNullable(desc.first()).map(Element::text).orElse(""));
                model.setPopularTags(Optional.of(popularTags).map(tags -> tags.stream().map(Element::text).collect(Collectors.toList())).orElse(new ArrayList<>()));
                model.setPullCount(Optional.ofNullable(pullCounts.first()).map(Element::text).orElse(""));
                model.setTotalTags(Optional.ofNullable(totalTags.first()).map(Element::text).map(Integer::parseInt).orElse(0));
                model.setLastUpdated(Optional.ofNullable(lastUpdatedTime.first()).map(Element::text).orElse(""));

                models.add(model);
            }
            return models;
        } else {
            throw new OllamaBaseException(statusCode + " - " + responseString);
        }
    }

    /**
     * Fetches the tags associated with a specific model from Ollama library.
     * This method fetches the available model tags directly from Ollama library model page, including model tag name, size and time when model was last updated
     * into a list of {@link LibraryModelTag} objects.
     *
     * @param libraryModel the {@link LibraryModel} object which contains the name of the library model
     *                     for which the tags need to be fetched.
     * @return a list of {@link LibraryModelTag} objects containing the extracted tags and their associated metadata.
     * @throws OllamaBaseException  if the HTTP response status code indicates an error (i.e., not 200 OK),
     *                              or if there is any other issue during the request or response processing.
     * @throws IOException          if an input/output exception occurs during the HTTP request or response handling.
     * @throws InterruptedException if the thread is interrupted while waiting for the HTTP response.
     * @throws URISyntaxException   if the URI format is incorrect or invalid.
     */
    public LibraryModelDetail getLibraryModelDetails(LibraryModel libraryModel) throws OllamaBaseException, IOException, InterruptedException, URISyntaxException {
        String url = String.format("https://ollama.com/library/%s/tags", libraryModel.getName());
        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest httpRequest = getRequestBuilderDefault(new URI(url)).header("Accept", "application/json").header("Content-type", "application/json").GET().build();
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        int statusCode = response.statusCode();
        String responseString = response.body();

        List<LibraryModelTag> libraryModelTags = new ArrayList<>();
        if (statusCode == 200) {
            Document doc = Jsoup.parse(responseString);
            Elements tagSections = doc.select("html > body > main > div > section > div > div > div:nth-child(n+2) > div");
            for (Element e : tagSections) {
                Elements tags = e.select("div > a > div");
                Elements tagsMetas = e.select("div > span");

                LibraryModelTag libraryModelTag = new LibraryModelTag();

                if (tags.first() == null || tags.isEmpty()) {
                    // if tag cannot be extracted, skip.
                    continue;
                }
                libraryModelTag.setName(libraryModel.getName());
                Optional.ofNullable(tags.first()).map(Element::text).ifPresent(libraryModelTag::setTag);
                libraryModelTag.setSize(Optional.ofNullable(tagsMetas.first()).map(element -> element.text().split("•")).filter(parts -> parts.length > 1).map(parts -> parts[1].trim()).orElse(""));
                libraryModelTag.setLastUpdated(Optional.ofNullable(tagsMetas.first()).map(element -> element.text().split("•")).filter(parts -> parts.length > 1).map(parts -> parts[2].trim()).orElse(""));
                libraryModelTags.add(libraryModelTag);
            }
            LibraryModelDetail libraryModelDetail = new LibraryModelDetail();
            libraryModelDetail.setModel(libraryModel);
            libraryModelDetail.setTags(libraryModelTags);
            return libraryModelDetail;
        } else {
            throw new OllamaBaseException(statusCode + " - " + responseString);
        }
    }

    /**
     * Finds a specific model using model name and tag from Ollama library.
     * <p>
     * This method retrieves the model from the Ollama library by its name, then fetches its tags.
     * It searches through the tags of the model to find one that matches the specified tag name.
     * If the model or the tag is not found, it throws a {@link NoSuchElementException}.
     *
     * @param modelName The name of the model to search for in the library.
     * @param tag       The tag name to search for within the specified model.
     * @return The {@link LibraryModelTag} associated with the specified model and tag.
     * @throws OllamaBaseException    If there is a problem with the Ollama library operations.
     * @throws IOException            If an I/O error occurs during the operation.
     * @throws URISyntaxException     If there is an error with the URI syntax.
     * @throws InterruptedException   If the operation is interrupted.
     * @throws NoSuchElementException If the model or the tag is not found.
     */
    public LibraryModelTag findModelTagFromLibrary(String modelName, String tag) throws OllamaBaseException, IOException, URISyntaxException, InterruptedException {
        List<LibraryModel> libraryModels = this.listModelsFromLibrary();
        LibraryModel libraryModel = libraryModels.stream().filter(model -> model.getName().equals(modelName)).findFirst().orElseThrow(() -> new NoSuchElementException(String.format("Model by name '%s' not found", modelName)));
        LibraryModelDetail libraryModelDetail = this.getLibraryModelDetails(libraryModel);
        LibraryModelTag libraryModelTag = libraryModelDetail.getTags().stream().filter(tagName -> tagName.getTag().equals(tag)).findFirst().orElseThrow(() -> new NoSuchElementException(String.format("Tag '%s' for model '%s' not found", tag, modelName)));
        return libraryModelTag;
    }

    /**
     * Pull a model on the Ollama server from the list of <a
     * href="https://ollama.ai/library">available models</a>.
     *
     * @param modelName the name of the model
     * @throws OllamaBaseException  if the response indicates an error status
     * @throws IOException          if an I/O error occurs during the HTTP request
     * @throws InterruptedException if the operation is interrupted
     * @throws URISyntaxException   if the URI for the request is malformed
     */
    public void pullModel(String modelName) throws OllamaBaseException, IOException, URISyntaxException, InterruptedException {
        String url = this.host + "/api/pull";
        String jsonData = new ModelRequest(modelName).toString();
        HttpRequest request = getRequestBuilderDefault(new URI(url)).POST(HttpRequest.BodyPublishers.ofString(jsonData)).header("Accept", "application/json").header("Content-type", "application/json").build();
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        int statusCode = response.statusCode();
        InputStream responseBodyStream = response.body();
        String responseString = "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(responseBodyStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                ModelPullResponse modelPullResponse = Utils.getObjectMapper().readValue(line, ModelPullResponse.class);
                if (verbose) {
                    logger.info(modelPullResponse.getStatus());
                }
            }
        }
        if (statusCode != 200) {
            throw new OllamaBaseException(statusCode + " - " + responseString);
        }
    }

    /**
     * Pulls a model using the specified Ollama library model tag.
     * The model is identified by a name and a tag, which are combined into a single identifier
     * in the format "name:tag" to pull the corresponding model.
     *
     * @param libraryModelTag the {@link LibraryModelTag} object containing the name and tag
     *                        of the model to be pulled.
     * @throws OllamaBaseException  if the response indicates an error status
     * @throws IOException          if an I/O error occurs during the HTTP request
     * @throws InterruptedException if the operation is interrupted
     * @throws URISyntaxException   if the URI for the request is malformed
     */
    public void pullModel(LibraryModelTag libraryModelTag) throws OllamaBaseException, IOException, URISyntaxException, InterruptedException {
        String tagToPull = String.format("%s:%s", libraryModelTag.getName(), libraryModelTag.getTag());
        pullModel(tagToPull);
    }

    /**
     * Gets model details from the Ollama server.
     *
     * @param modelName the model
     * @return the model details
     * @throws OllamaBaseException  if the response indicates an error status
     * @throws IOException          if an I/O error occurs during the HTTP request
     * @throws InterruptedException if the operation is interrupted
     * @throws URISyntaxException   if the URI for the request is malformed
     */
    public ModelDetail getModelDetails(String modelName) throws IOException, OllamaBaseException, InterruptedException, URISyntaxException {
        String url = this.host + "/api/show";
        String jsonData = new ModelRequest(modelName).toString();
        HttpRequest request = getRequestBuilderDefault(new URI(url)).header("Accept", "application/json").header("Content-type", "application/json").POST(HttpRequest.BodyPublishers.ofString(jsonData)).build();
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        int statusCode = response.statusCode();
        String responseBody = response.body();
        if (statusCode == 200) {
            return Utils.getObjectMapper().readValue(responseBody, ModelDetail.class);
        } else {
            throw new OllamaBaseException(statusCode + " - " + responseBody);
        }
    }

    /**
     * Create a custom model from a model file. Read more about custom model file creation <a
     * href="https://github.com/jmorganca/ollama/blob/main/docs/modelfile.md">here</a>.
     *
     * @param modelName     the name of the custom model to be created.
     * @param modelFilePath the path to model file that exists on the Ollama server.
     * @throws OllamaBaseException  if the response indicates an error status
     * @throws IOException          if an I/O error occurs during the HTTP request
     * @throws InterruptedException if the operation is interrupted
     * @throws URISyntaxException   if the URI for the request is malformed
     */
    public void createModelWithFilePath(String modelName, String modelFilePath) throws IOException, InterruptedException, OllamaBaseException, URISyntaxException {
        String url = this.host + "/api/create";
        String jsonData = new CustomModelFilePathRequest(modelName, modelFilePath).toString();
        HttpRequest request = getRequestBuilderDefault(new URI(url)).header("Accept", "application/json").header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(jsonData, StandardCharsets.UTF_8)).build();
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        int statusCode = response.statusCode();
        String responseString = response.body();
        if (statusCode != 200) {
            throw new OllamaBaseException(statusCode + " - " + responseString);
        }
        // FIXME: Ollama API returns HTTP status code 200 for model creation failure cases. Correct this
        // if the issue is fixed in the Ollama API server.
        if (responseString.contains("error")) {
            throw new OllamaBaseException(responseString);
        }
        if (verbose) {
            logger.info(responseString);
        }
    }

    /**
     * Create a custom model from a model file. Read more about custom model file creation <a
     * href="https://github.com/jmorganca/ollama/blob/main/docs/modelfile.md">here</a>.
     *
     * @param modelName         the name of the custom model to be created.
     * @param modelFileContents the path to model file that exists on the Ollama server.
     * @throws OllamaBaseException  if the response indicates an error status
     * @throws IOException          if an I/O error occurs during the HTTP request
     * @throws InterruptedException if the operation is interrupted
     * @throws URISyntaxException   if the URI for the request is malformed
     */
    public void createModelWithModelFileContents(String modelName, String modelFileContents) throws IOException, InterruptedException, OllamaBaseException, URISyntaxException {
        String url = this.host + "/api/create";
        String jsonData = new CustomModelFileContentsRequest(modelName, modelFileContents).toString();
        HttpRequest request = getRequestBuilderDefault(new URI(url)).header("Accept", "application/json").header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(jsonData, StandardCharsets.UTF_8)).build();
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        int statusCode = response.statusCode();
        String responseString = response.body();
        if (statusCode != 200) {
            throw new OllamaBaseException(statusCode + " - " + responseString);
        }
        if (responseString.contains("error")) {
            throw new OllamaBaseException(responseString);
        }
        if (verbose) {
            logger.info(responseString);
        }
    }

    /**
     * Delete a model from Ollama server.
     *
     * @param modelName          the name of the model to be deleted.
     * @param ignoreIfNotPresent ignore errors if the specified model is not present on Ollama server.
     * @throws OllamaBaseException  if the response indicates an error status
     * @throws IOException          if an I/O error occurs during the HTTP request
     * @throws InterruptedException if the operation is interrupted
     * @throws URISyntaxException   if the URI for the request is malformed
     */
    public void deleteModel(String modelName, boolean ignoreIfNotPresent) throws IOException, InterruptedException, OllamaBaseException, URISyntaxException {
        String url = this.host + "/api/delete";
        String jsonData = new ModelRequest(modelName).toString();
        HttpRequest request = getRequestBuilderDefault(new URI(url)).method("DELETE", HttpRequest.BodyPublishers.ofString(jsonData, StandardCharsets.UTF_8)).header("Accept", "application/json").header("Content-type", "application/json").build();
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        int statusCode = response.statusCode();
        String responseBody = response.body();
        if (statusCode == 404 && responseBody.contains("model") && responseBody.contains("not found")) {
            return;
        }
        if (statusCode != 200) {
            throw new OllamaBaseException(statusCode + " - " + responseBody);
        }
    }

    /**
     * Generate embeddings for a given text from a model
     *
     * @param model  name of model to generate embeddings from
     * @param prompt text to generate embeddings for
     * @return embeddings
     * @throws OllamaBaseException  if the response indicates an error status
     * @throws IOException          if an I/O error occurs during the HTTP request
     * @throws InterruptedException if the operation is interrupted
     * @deprecated Use {@link #embed(String, List)} instead.
     */
    @Deprecated
    public List<Double> generateEmbeddings(String model, String prompt) throws IOException, InterruptedException, OllamaBaseException {
        return generateEmbeddings(new OllamaEmbeddingsRequestModel(model, prompt));
    }

    /**
     * Generate embeddings using a {@link OllamaEmbeddingsRequestModel}.
     *
     * @param modelRequest request for '/api/embeddings' endpoint
     * @return embeddings
     * @throws OllamaBaseException  if the response indicates an error status
     * @throws IOException          if an I/O error occurs during the HTTP request
     * @throws InterruptedException if the operation is interrupted
     * @deprecated Use {@link #embed(OllamaEmbedRequestModel)} instead.
     */
    @Deprecated
    public List<Double> generateEmbeddings(OllamaEmbeddingsRequestModel modelRequest) throws IOException, InterruptedException, OllamaBaseException {
        URI uri = URI.create(this.host + "/api/embeddings");
        String jsonData = modelRequest.toString();
        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest.Builder requestBuilder = getRequestBuilderDefault(uri).header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(jsonData));
        HttpRequest request = requestBuilder.build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        int statusCode = response.statusCode();
        String responseBody = response.body();
        if (statusCode == 200) {
            OllamaEmbeddingResponseModel embeddingResponse = Utils.getObjectMapper().readValue(responseBody, OllamaEmbeddingResponseModel.class);
            return embeddingResponse.getEmbedding();
        } else {
            throw new OllamaBaseException(statusCode + " - " + responseBody);
        }
    }

    /**
     * Generate embeddings for a given text from a model
     *
     * @param model  name of model to generate embeddings from
     * @param inputs text/s to generate embeddings for
     * @return embeddings
     * @throws OllamaBaseException  if the response indicates an error status
     * @throws IOException          if an I/O error occurs during the HTTP request
     * @throws InterruptedException if the operation is interrupted
     */
    public OllamaEmbedResponseModel embed(String model, List<String> inputs) throws IOException, InterruptedException, OllamaBaseException {
        return embed(new OllamaEmbedRequestModel(model, inputs));
    }

    /**
     * Generate embeddings using a {@link OllamaEmbedRequestModel}.
     *
     * @param modelRequest request for '/api/embed' endpoint
     * @return embeddings
     * @throws OllamaBaseException  if the response indicates an error status
     * @throws IOException          if an I/O error occurs during the HTTP request
     * @throws InterruptedException if the operation is interrupted
     */
    public OllamaEmbedResponseModel embed(OllamaEmbedRequestModel modelRequest) throws IOException, InterruptedException, OllamaBaseException {
        URI uri = URI.create(this.host + "/api/embed");
        String jsonData = Utils.getObjectMapper().writeValueAsString(modelRequest);
        HttpClient httpClient = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder(uri).header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(jsonData)).build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        int statusCode = response.statusCode();
        String responseBody = response.body();

        if (statusCode == 200) {
            return Utils.getObjectMapper().readValue(responseBody, OllamaEmbedResponseModel.class);
        } else {
            throw new OllamaBaseException(statusCode + " - " + responseBody);
        }
    }

    /**
     * Generate response for a question to a model running on Ollama server. This is a sync/blocking
     * call.
     *
     * @param model         the ollama model to ask the question to
     * @param prompt        the prompt/question text
     * @param options       the Options object - <a
     *                      href="https://github.com/jmorganca/ollama/blob/main/docs/modelfile.md#valid-parameters-and-values">More
     *                      details on the options</a>
     * @return OllamaResult that includes response text and time taken for response
     * @throws OllamaBaseException  if the response indicates an error status
     * @throws IOException          if an I/O error occurs during the HTTP request
     * @throws InterruptedException if the operation is interrupted
     */
    public OllamaResult generate(String model, String prompt, boolean raw, Options options) throws OllamaBaseException, IOException, InterruptedException {
        OllamaGenerateRequest ollamaRequestModel = new OllamaGenerateRequest(model, prompt);
        ollamaRequestModel.setRaw(raw);
        ollamaRequestModel.setOptions(options.getOptionsMap());
        return generateSyncForOllamaRequestModel(ollamaRequestModel, null);
    }

    /**
     * Generate response for a question to a model running on Ollama server. This is a sync/blocking
     * call.
     *
     * @param model         the ollama model to ask the question to
     * @param prompt        the prompt/question text
     * @param options       the Options object - <a
     *                      href="https://github.com/jmorganca/ollama/blob/main/docs/modelfile.md#valid-parameters-and-values">More
     *                      details on the options</a>
     * @param streamHandler optional callback consumer that will be applied every time a streamed response is received. If not set, the stream parameter of the request is set to false.
     * @return OllamaResult that includes response text and time taken for response
     * @throws OllamaBaseException  if the response indicates an error status
     * @throws IOException          if an I/O error occurs during the HTTP request
     * @throws InterruptedException if the operation is interrupted
     */
    public OllamaResult generate(String model, String prompt, boolean raw, Options options, OllamaStreamHandler streamHandler) throws OllamaBaseException, IOException, InterruptedException {
        OllamaGenerateRequest ollamaRequestModel = new OllamaGenerateRequest(model, prompt);
        ollamaRequestModel.setRaw(raw);
        ollamaRequestModel.setOptions(options.getOptionsMap());
        return generateSyncForOllamaRequestModel(ollamaRequestModel, streamHandler);
    }

    /**
     * Generate response for a question to a model running on Ollama server. This is a sync/blocking
     * call.
     *
     * @param model         the ollama model to ask the question to
     * @param prompt        the prompt/question text
     * @param options       the Options object - <a
     *                      href="https://github.com/jmorganca/ollama/blob/main/docs/modelfile.md#valid-parameters-and-values">More
     *                      details on the options</a>
     * @param streamHandler optional callback consumer that will be applied every time a streamed response is received. If not set, the stream parameter of the request is set to false.
     * @param format        Class used as a marshalling model during structured output generation.
     * @return OllamaResult that includes response text and time taken for response
     * @throws OllamaBaseException  if the response indicates an error status
     * @throws IOException          if an I/O error occurs during the HTTP request
     * @throws InterruptedException if the operation is interrupted
     */
    public OllamaResult generate(String model, String prompt, boolean raw, Options options, OllamaStreamHandler streamHandler, Class<?> format) throws OllamaBaseException, IOException, InterruptedException {
        OllamaGenerateRequest ollamaRequestModel = new OllamaGenerateRequest(model, prompt);
        ollamaRequestModel.setRaw(raw);
        ollamaRequestModel.setOptions(options.getOptionsMap());
        ollamaRequestModel.setResponseClass(format);
        return generateSyncForOllamaRequestModel(ollamaRequestModel, streamHandler);
    }

    /**
     * Generates response using the specified AI model and prompt (in blocking mode).
     * <p>
     * Uses {@link #generate(String, String, boolean, Options, OllamaStreamHandler)}
     *
     * @param model   The name or identifier of the AI model to use for generating the response.
     * @param prompt  The input text or prompt to provide to the AI model.
     * @param raw     In some cases, you may wish to bypass the templating system and provide a full prompt. In this case, you can use the raw parameter to disable templating. Also note that raw mode will not return a context.
     * @param options Additional options or configurations to use when generating the response.
     * @param format  Class used as a marshalling model during structured output generation.
     * @return {@link OllamaResult}
     * @throws OllamaBaseException  if the response indicates an error status
     * @throws IOException          if an I/O error occurs during the HTTP request
     * @throws InterruptedException if the operation is interrupted
     */
    public OllamaResult generate(String model, String prompt, boolean raw, Options options, Class<?> format) throws OllamaBaseException, IOException, InterruptedException {
        return generate(model, prompt, raw, options, null, format);
    }

    /**
     * Generates response using the specified AI model and prompt (in blocking mode), and then invokes a set of tools
     * on the generated response.
     *
     * @param model   The name or identifier of the AI model to use for generating the response.
     * @param prompt  The input text or prompt to provide to the AI model.
     * @param options Additional options or configurations to use when generating the response.
     * @return {@link OllamaToolsResult} An OllamaToolsResult object containing the response from the AI model and the results of invoking the tools on that output.
     * @throws OllamaBaseException  if the response indicates an error status
     * @throws IOException          if an I/O error occurs during the HTTP request
     * @throws InterruptedException if the operation is interrupted
     */
    public OllamaToolsResult generateWithTools(String model, String prompt, Options options) throws OllamaBaseException, IOException, InterruptedException, ToolInvocationException {
        boolean raw = true;
        OllamaToolsResult toolResult = new OllamaToolsResult();
        Map<ToolFunctionCallSpec, Object> toolResults = new HashMap<>();

        OllamaResult result = generate(model, prompt, raw, options, null, null);
        toolResult.setModelResult(result);

        String toolsResponse = result.getResponse();
        if (toolsResponse.contains("[TOOL_CALLS]")) {
            toolsResponse = toolsResponse.replace("[TOOL_CALLS]", "");
        }

        List<ToolFunctionCallSpec> toolFunctionCallSpecs = Utils.getObjectMapper().readValue(toolsResponse, Utils.getObjectMapper().getTypeFactory().constructCollectionType(List.class, ToolFunctionCallSpec.class));
        for (ToolFunctionCallSpec toolFunctionCallSpec : toolFunctionCallSpecs) {
            toolResults.put(toolFunctionCallSpec, invokeTool(toolFunctionCallSpec));
        }
        toolResult.setToolResults(toolResults);
        return toolResult;
    }

    /**
     * Generate response for a question to a model running on Ollama server and get a callback handle
     * that can be used to check for status and get the response from the model later. This would be
     * an async/non-blocking call.
     *
     * @param model  the ollama model to ask the question to
     * @param prompt the prompt/question text
     * @return the ollama async result callback handle
     */
    public OllamaAsyncResultStreamer generateAsync(String model, String prompt, boolean raw) {
        OllamaGenerateRequest ollamaRequestModel = new OllamaGenerateRequest(model, prompt);
        ollamaRequestModel.setRaw(raw);
        URI uri = URI.create(this.host + "/api/generate");
        OllamaAsyncResultStreamer ollamaAsyncResultStreamer = new OllamaAsyncResultStreamer(getRequestBuilderDefault(uri), ollamaRequestModel, requestTimeoutSeconds);
        ollamaAsyncResultStreamer.start();
        return ollamaAsyncResultStreamer;
    }

    /**
     * With one or more image files, ask a question to a model running on Ollama server. This is a
     * sync/blocking call.
     *
     * @param model         the ollama model to ask the question to
     * @param prompt        the prompt/question text
     * @param imageFiles    the list of image files to use for the question
     * @param options       the Options object - <a
     *                      href="https://github.com/jmorganca/ollama/blob/main/docs/modelfile.md#valid-parameters-and-values">More
     *                      details on the options</a>
     * @param streamHandler optional callback consumer that will be applied every time a streamed response is received. If not set, the stream parameter of the request is set to false.
     * @return OllamaResult that includes response text and time taken for response
     * @throws OllamaBaseException  if the response indicates an error status
     * @throws IOException          if an I/O error occurs during the HTTP request
     * @throws InterruptedException if the operation is interrupted
     */
    public OllamaResult generateWithImageFiles(String model, String prompt, List<File> imageFiles, Options options, OllamaStreamHandler streamHandler) throws OllamaBaseException, IOException, InterruptedException {
        List<String> images = new ArrayList<>();
        for (File imageFile : imageFiles) {
            images.add(encodeFileToBase64(imageFile));
        }
        OllamaGenerateRequest ollamaRequestModel = new OllamaGenerateRequest(model, prompt, images);
        ollamaRequestModel.setOptions(options.getOptionsMap());
        return generateSyncForOllamaRequestModel(ollamaRequestModel, streamHandler);
    }

    /**
     * Convenience method to call Ollama API without streaming responses.
     * <p>
     * Uses {@link #generateWithImageFiles(String, String, List, Options, OllamaStreamHandler)}
     *
     * @throws OllamaBaseException  if the response indicates an error status
     * @throws IOException          if an I/O error occurs during the HTTP request
     * @throws InterruptedException if the operation is interrupted
     */
    public OllamaResult generateWithImageFiles(String model, String prompt, List<File> imageFiles, Options options) throws OllamaBaseException, IOException, InterruptedException {
        return generateWithImageFiles(model, prompt, imageFiles, options, null);
    }

    /**
     * With one or more image URLs, ask a question to a model running on Ollama server. This is a
     * sync/blocking call.
     *
     * @param model         the ollama model to ask the question to
     * @param prompt        the prompt/question text
     * @param imageURLs     the list of image URLs to use for the question
     * @param options       the Options object - <a
     *                      href="https://github.com/jmorganca/ollama/blob/main/docs/modelfile.md#valid-parameters-and-values">More
     *                      details on the options</a>
     * @param streamHandler optional callback consumer that will be applied every time a streamed response is received. If not set, the stream parameter of the request is set to false.
     * @return OllamaResult that includes response text and time taken for response
     * @throws OllamaBaseException  if the response indicates an error status
     * @throws IOException          if an I/O error occurs during the HTTP request
     * @throws InterruptedException if the operation is interrupted
     * @throws URISyntaxException   if the URI for the request is malformed
     */
    public OllamaResult generateWithImageURLs(String model, String prompt, List<String> imageURLs, Options options, OllamaStreamHandler streamHandler) throws OllamaBaseException, IOException, InterruptedException, URISyntaxException {
        List<String> images = new ArrayList<>();
        for (String imageURL : imageURLs) {
            images.add(encodeByteArrayToBase64(Utils.loadImageBytesFromUrl(imageURL)));
        }
        OllamaGenerateRequest ollamaRequestModel = new OllamaGenerateRequest(model, prompt, images);
        ollamaRequestModel.setOptions(options.getOptionsMap());
        return generateSyncForOllamaRequestModel(ollamaRequestModel, streamHandler);
    }

    /**
     * Convenience method to call Ollama API without streaming responses.
     * <p>
     * Uses {@link #generateWithImageURLs(String, String, List, Options, OllamaStreamHandler)}
     *
     * @throws OllamaBaseException  if the response indicates an error status
     * @throws IOException          if an I/O error occurs during the HTTP request
     * @throws InterruptedException if the operation is interrupted
     * @throws URISyntaxException   if the URI for the request is malformed
     */
    public OllamaResult generateWithImageURLs(String model, String prompt, List<String> imageURLs, Options options) throws OllamaBaseException, IOException, InterruptedException, URISyntaxException {
        return generateWithImageURLs(model, prompt, imageURLs, options, null);
    }

    /**
     * Ask a question to a model based on a given message stack (i.e. a chat history). Creates a synchronous call to the api
     * 'api/chat'.
     *
     * @param model    the ollama model to ask the question to
     * @param messages chat history / message stack to send to the model
     * @return {@link OllamaChatResult} containing the api response and the message history including the newly aqcuired assistant response.
     * @throws OllamaBaseException  any response code than 200 has been returned
     * @throws IOException          in case the responseStream can not be read
     * @throws InterruptedException in case the server is not reachable or network issues happen
     * @throws OllamaBaseException  if the response indicates an error status
     * @throws IOException          if an I/O error occurs during the HTTP request
     * @throws InterruptedException if the operation is interrupted
     */
    public OllamaChatResult chat(String model, List<OllamaChatMessage> messages) throws OllamaBaseException, IOException, InterruptedException {
        OllamaChatRequestBuilder builder = OllamaChatRequestBuilder.getInstance(model);
        return chat(builder.withMessages(messages).build());
    }

    /**
     * Ask a question to a model using an {@link OllamaChatRequest}. This can be constructed using an {@link OllamaChatRequestBuilder}.
     * <p>
     * Hint: the OllamaChatRequestModel#getStream() property is not implemented.
     *
     * @param request request object to be sent to the server
     * @return {@link OllamaChatResult}
     * @throws OllamaBaseException  any response code than 200 has been returned
     * @throws IOException          in case the responseStream can not be read
     * @throws InterruptedException in case the server is not reachable or network issues happen
     * @throws OllamaBaseException  if the response indicates an error status
     * @throws IOException          if an I/O error occurs during the HTTP request
     * @throws InterruptedException if the operation is interrupted
     */
    public OllamaChatResult chat(OllamaChatRequest request) throws OllamaBaseException, IOException, InterruptedException {
        return chat(request, null);
    }

    /**
     * Ask a question to a model using an {@link OllamaChatRequest}. This can be constructed using an {@link OllamaChatRequestBuilder}.
     * <p>
     * Hint: the OllamaChatRequestModel#getStream() property is not implemented.
     *
     * @param request       request object to be sent to the server
     * @param streamHandler callback handler to handle the last message from stream (caution: all previous messages from stream will be concatenated)
     * @return {@link OllamaChatResult}
     * @throws OllamaBaseException  any response code than 200 has been returned
     * @throws IOException          in case the responseStream can not be read
     * @throws InterruptedException in case the server is not reachable or network issues happen
     * @throws OllamaBaseException  if the response indicates an error status
     * @throws IOException          if an I/O error occurs during the HTTP request
     * @throws InterruptedException if the operation is interrupted
     */
    public OllamaChatResult chat(OllamaChatRequest request, OllamaStreamHandler streamHandler) throws OllamaBaseException, IOException, InterruptedException {
        OllamaChatEndpointCaller requestCaller = new OllamaChatEndpointCaller(host, basicAuth, requestTimeoutSeconds, verbose);
        OllamaResult result;
        Class<?> requestClass = request.getResponseClass();
        if (streamHandler != null) {
            request.setStream(true);
            result = requestCaller.call(request, streamHandler);
        } else {
            result = requestCaller.callSync(request);
        }
        return new OllamaChatResult(result.getResponse(), result.getResponseTime(), result.getHttpStatusCode(), request.getMessages());
    }

    public void registerTool(Tools.ToolSpecification toolSpecification) {
        toolRegistry.addFunction(toolSpecification.getFunctionName(), toolSpecification.getToolDefinition());
    }

    /**
     * Adds a custom role.
     *
     * @param roleName the name of the custom role to be added
     * @return the newly created OllamaChatMessageRole
     */
    public OllamaChatMessageRole addCustomRole(String roleName) {
        return OllamaChatMessageRole.newCustomRole(roleName);
    }

    /**
     * Lists all available roles.
     *
     * @return a list of available OllamaChatMessageRole objects
     */
    public List<OllamaChatMessageRole> listRoles() {
        return OllamaChatMessageRole.getRoles();
    }

    /**
     * Retrieves a specific role by name.
     *
     * @param roleName the name of the role to retrieve
     * @return the OllamaChatMessageRole associated with the given name
     * @throws RoleNotFoundException if the role with the specified name does not exist
     */
    public OllamaChatMessageRole getRole(String roleName) throws RoleNotFoundException {
        return OllamaChatMessageRole.getRole(roleName);
    }


    // technical private methods //

    private static String encodeFileToBase64(File file) throws IOException {
        return Base64.getEncoder().encodeToString(Files.readAllBytes(file.toPath()));
    }

    private static String encodeByteArrayToBase64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private OllamaResult generateSyncForOllamaRequestModel(OllamaGenerateRequest ollamaRequestModel, OllamaStreamHandler streamHandler) throws OllamaBaseException, IOException, InterruptedException {
        OllamaGenerateEndpointCaller requestCaller = new OllamaGenerateEndpointCaller(host, basicAuth, requestTimeoutSeconds, verbose);
        OllamaResult result;
        if (streamHandler != null) {
            ollamaRequestModel.setStream(true);
            result = requestCaller.call(ollamaRequestModel, streamHandler);
        } else {
            result = requestCaller.callSync(ollamaRequestModel);
        }
        return result;
    }

    /**
     * Get default request builder.
     *
     * @param uri URI to get a HttpRequest.Builder
     * @return HttpRequest.Builder
     */
    private HttpRequest.Builder getRequestBuilderDefault(URI uri) {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri).header("Content-Type", "application/json").timeout(Duration.ofSeconds(requestTimeoutSeconds));
        if (isBasicAuthCredentialsSet()) {
            requestBuilder.header("Authorization", getBasicAuthHeaderValue());
        }
        return requestBuilder;
    }

    /**
     * Get basic authentication header value.
     *
     * @return basic authentication header value (encoded credentials)
     */
    private String getBasicAuthHeaderValue() {
        String credentialsToEncode = basicAuth.getUsername() + ":" + basicAuth.getPassword();
        return "Basic " + Base64.getEncoder().encodeToString(credentialsToEncode.getBytes());
    }

    /**
     * Check if Basic Auth credentials set.
     *
     * @return true when Basic Auth credentials set
     */
    private boolean isBasicAuthCredentialsSet() {
        return basicAuth != null;
    }

    private Object invokeTool(ToolFunctionCallSpec toolFunctionCallSpec) throws ToolInvocationException {
        try {
            String methodName = toolFunctionCallSpec.getName();
            Map<String, Object> arguments = toolFunctionCallSpec.getArguments();
            ToolFunction function = toolRegistry.getFunction(methodName);
            if (verbose) {
                logger.debug("Invoking function {} with arguments {}", methodName, arguments);
            }
            if (function == null) {
                throw new ToolNotFoundException("No such tool: " + methodName);
            }
            return function.apply(arguments);
        } catch (Exception e) {
            throw new ToolInvocationException("Failed to invoke tool: " + toolFunctionCallSpec.getName(), e);
        }
    }
}
