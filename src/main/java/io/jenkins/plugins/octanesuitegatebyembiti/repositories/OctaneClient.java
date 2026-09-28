package io.jenkins.plugins.octanesuitegatebyembiti.repositories;

import hudson.AbortException;
import hudson.util.Secret;
import io.jenkins.plugins.octanesuitegatebyembiti.configs.OctaneServerUrl;
import io.jenkins.plugins.octanesuitegatebyembiti.entities.DefectRecord;
import io.jenkins.plugins.octanesuitegatebyembiti.entities.RunRecord;
import io.jenkins.plugins.octanesuitegatebyembiti.utils.Util;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

public class OctaneClient implements AutoCloseable {
  private static final int PAGE_SIZE = 200;
  private static final int QUERY_CHUNK_SIZE = 40;
  private static final int MAX_ATTEMPTS = 3;
  private static final int RESPONSE_BODY_LIMIT = 1_000;
  private static final int MAX_LOCKED_ATTRIBUTIONS = 20_000;
  static final int MAX_JSON_RESPONSE_BYTES = 16 * 1024 * 1024;
  private static final Pattern SAFE_ENTITY_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
  private static final Pattern SENSITIVE_RESPONSE_VALUE =
      Pattern.compile(
          "(?i)(\\\"(?:client_secret|password|token|access_token|refresh_token|authorization)\\\"\\s*:\\s*\\\")[^\\\"]*(\\\")");
  private static final String TECH_PREVIEW_HEADER = "ALM-OCTANE-TECH-PREVIEW";
  private static final String SAFE_RUN_FIELDS =
      "id,name,native_status{logical_name,name},status{logical_name,name},run_by{id,name},"
          + "test{id,name},product_areas{id,name},runs_in_suite";
  private static final String RUN_FIELDS = SAFE_RUN_FIELDS + ",native_tester{id,name}";
  private static final String SAFE_CHILD_RUN_FIELDS =
      "id,name,native_status{logical_name,name},status{logical_name,name},run_by{id,name},"
          + "subtype,test{id,name},product_areas{id,name}";
  private static final String CHILD_RUN_FIELDS = SAFE_CHILD_RUN_FIELDS + ",native_tester{id,name}";
  private static final String SUITE_RUN_FIELDS = RUN_FIELDS + ",owner{id,name}";
  private static final String SAFE_SUITE_RUN_FIELDS = SAFE_RUN_FIELDS + ",owner{id,name}";
  private static final String DEFAULT_RUN_BY_SUITE_RUN_FIELDS =
      RUN_FIELDS + ",default_run_by{id,name}";
  private static final String SAFE_DEFAULT_RUN_BY_SUITE_RUN_FIELDS =
      SAFE_RUN_FIELDS + ",default_run_by{id,name}";
  private static final String ASSIGNED_TO_SUITE_RUN_FIELDS = RUN_FIELDS + ",assigned_to{id,name}";
  private static final String SAFE_ASSIGNED_TO_SUITE_RUN_FIELDS =
      SAFE_RUN_FIELDS + ",assigned_to{id,name}";
  private static final String ASSIGNEE_SUITE_RUN_FIELDS = RUN_FIELDS + ",assignee{id,name}";
  private static final String SAFE_ASSIGNEE_SUITE_RUN_FIELDS =
      SAFE_RUN_FIELDS + ",assignee{id,name}";
  private static final String DEFAULT_RUN_BY_FIELDS = "id,default_run_by{id,name}";
  private static final String PARENT_RUN_BY_FIELDS = "id,run_by{id,name}";
  private static final String SUITE_ASSIGNED_TO_FIELDS = "id,assigned_to{id,name}";
  private static final String SUITE_ASSIGNEE_FIELDS = "id,assignee{id,name}";
  private static final String SUITE_OWNER_FIELDS = "id,owner{id,name}";
  private static final String SUITE_TEST_FIELDS = "id,test{id,name}";
  private static final Pattern SYSTEM_PARENT_RUNNER =
      Pattern.compile("(?:jenkins|default\\s+manual\\s+runner)", Pattern.CASE_INSENSITIVE);
  private static final String EXTENDED_DEFECT_FIELDS =
      "id,name,description,severity{logical_name,name},priority{logical_name,name},"
          + "phase{logical_name,name},"
          + "run{id,name},detected_in_run{id,name},test{id,name},owner_test{id,name},"
          + "product_areas{id,name}";
  private static final String DEFECT_FIELDS =
      "id,name,description,severity{logical_name,name},priority{logical_name,name},"
          + "phase{logical_name,name},"
          + "run{id,name},test{id,name},product_areas{id,name}";
  private static final String MINIMAL_DEFECT_FIELDS =
      "id,name,description,severity{logical_name,name},priority{logical_name,name},"
          + "phase{logical_name,name}";
  private static final HttpClient SHARED_HTTP_CLIENT =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(30))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final String baseUrl;
  private final String clientId;
  private final Secret clientSecret;
  private final Object childRunFieldProfileLock = new Object();
  // Parent suite ownership is immutable for this client session. Child execution actors never
  // enter this map and are retained only on RunRecord for automation metrics.
  private final Map<String, String> lockedSuiteOwners = new LinkedHashMap<>(256, 0.75f, true);
  private volatile String preferredChildRunFields = "";
  private volatile String preferredDefectFields = "";
  private volatile String preferredRunSuiteFields = "";
  private volatile String preferredSuiteEntityFields = "";
  private Secret cookieHeader;

  public OctaneClient(String baseUrl, String clientId, Secret clientSecret) {
    this(SHARED_HTTP_CLIENT, baseUrl, clientId, clientSecret);
  }

  public static OctaneClient withCredentials(String baseUrl, String clientId, Secret clientSecret) {
    return new OctaneClient(SHARED_HTTP_CLIENT, baseUrl, clientId, clientSecret);
  }

  public OctaneClient(HttpClient httpClient, String baseUrl, String clientId, Secret clientSecret) {
    if (httpClient.followRedirects() != HttpClient.Redirect.NEVER) {
      throw new IllegalArgumentException(
          "Octane HTTP clients must not follow credential redirects.");
    }
    this.httpClient = httpClient;
    this.baseUrl = OctaneServerUrl.normalize(baseUrl);
    this.clientId = clientId;
    this.clientSecret = clientSecret == null ? Secret.fromString("") : clientSecret;
  }

  public void authenticate() throws IOException, InterruptedException {
    HttpRequest.Builder authentication = requestBuilder(baseUrl + "/authentication/sign_in");
    ObjectNode payload = objectMapper.createObjectNode();
    payload.put("client_id", clientId);
    payload.put("client_secret", clientSecret.getPlainText());

    HttpRequest request =
        authentication
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
            .build();
    StringResponse response = sendForString(request);
    if (response.statusCode() != 200) {
      throw new AbortException(
          "ALM Octane authentication failed with HTTP "
              + response.statusCode()
              + " for "
              + request.uri()
              + ". Authentication response body omitted to protect credentials.");
    }
    rememberCookies(response.headers());
  }

  public List<RunRecord> fetchSuiteChildRuns(
      String sharedSpaceId, String workspaceId, String suiteRunId)
      throws IOException, InterruptedException {
    JsonNode suiteRun = fetchSuiteRun(sharedSpaceId, workspaceId, suiteRunId);
    List<String> runIds = parseRunsInSuite(suiteRun);
    String suiteOwnerName = resolveSuiteOwnerName(sharedSpaceId, workspaceId, suiteRunId, suiteRun);
    if (runIds.isEmpty()) {
      return attributeSuiteRuns(
          suiteAssignmentKey(sharedSpaceId, workspaceId, suiteRunId),
          suiteRunId,
          suiteOwnerName,
          List.of(parseRun(suiteRun).withExecutionActorName("")));
    }
    return attributeSuiteRuns(
        suiteAssignmentKey(sharedSpaceId, workspaceId, suiteRunId),
        suiteRunId,
        suiteOwnerName,
        fetchRunsByIds(sharedSpaceId, workspaceId, runIds, ""));
  }

  public Map<String, List<RunRecord>> fetchSuiteChildRuns(
      String sharedSpaceId, String workspaceId, List<String> suiteRunIds)
      throws IOException, InterruptedException {
    return fetchSuiteChildRuns(sharedSpaceId, workspaceId, suiteRunIds, false);
  }

  /** Fetches all currently reachable suites while omitting suite runs confirmed as missing. */
  public Map<String, List<RunRecord>> fetchAvailableSuiteChildRuns(
      String sharedSpaceId, String workspaceId, List<String> suiteRunIds)
      throws IOException, InterruptedException {
    return fetchSuiteChildRuns(sharedSpaceId, workspaceId, suiteRunIds, true);
  }

  /** Resolves suite runs assigned to the named release and, when supplied, sprint. */
  public List<String> fetchSuiteRunIdsByReleaseAndSprint(
      String sharedSpaceId, String workspaceId, String releaseName, String sprintName)
      throws IOException, InterruptedException {
    String releaseFilter = "release EQ {name EQ " + octaneStringLiteral(releaseName) + "}";
    String sprintFilter =
        Util.isBlank(sprintName)
            ? ""
            : ";sprint EQ {name EQ " + octaneStringLiteral(sprintName) + "}";
    String relationshipQuery = releaseFilter + sprintFilter;
    String aggregateQuery = "test EQ {subtype EQ ^test_suite^};" + relationshipQuery;
    try {
      return fetchEntityIds(sharedSpaceId, workspaceId, "runs", aggregateQuery);
    } catch (IOException aggregateFailure) {
      try {
        return fetchEntityIds(sharedSpaceId, workspaceId, "suite_runs", relationshipQuery);
      } catch (IOException suiteRunsFailure) {
        throw new AbortException(
            "ALM Octane dynamic suite discovery failed. Runs collection lookup failed: "
                + aggregateFailure.getMessage()
                + ". suite_runs fallback failed: "
                + suiteRunsFailure.getMessage());
      }
    }
  }

  private List<String> fetchEntityIds(
      String sharedSpaceId, String workspaceId, String entityName, String query)
      throws IOException, InterruptedException {
    LinkedHashSet<String> suiteRunIds = new LinkedHashSet<>();
    int offset = 0;
    while (true) {
      String path =
          workspacePath(sharedSpaceId, workspaceId)
              + "/"
              + entityName
              + "?"
              + parameter("query", quote(query))
              + "&"
              + parameter("fields", "id")
              + "&"
              + parameter("limit", Integer.toString(PAGE_SIZE))
              + "&"
              + parameter("offset", Integer.toString(offset));
      JsonNode data = getJson(path).path("data");
      if (!data.isArray() || data.isEmpty()) {
        break;
      }
      for (JsonNode node : data) {
        String id = node.path("id").asString();
        if (!id.isEmpty()) {
          suiteRunIds.add(safeEntityId(id));
        }
      }
      if (data.size() < PAGE_SIZE) {
        break;
      }
      offset += PAGE_SIZE;
    }
    return List.copyOf(suiteRunIds);
  }

  private Map<String, List<RunRecord>> fetchSuiteChildRuns(
      String sharedSpaceId,
      String workspaceId,
      List<String> suiteRunIds,
      boolean tolerateMissingSuites)
      throws IOException, InterruptedException {
    if (suiteRunIds.isEmpty()) {
      return Map.of();
    }
    SuitePollPlan pollPlan =
        preFetchSuitePoll(sharedSpaceId, workspaceId, suiteRunIds, tolerateMissingSuites);
    Map<String, OctaneSuiteTopologyCache.Topology> topology = pollPlan.topology();
    List<RunRecord> childRuns =
        fetchRunsByIds(sharedSpaceId, workspaceId, pollPlan.childRunIds(), "");
    return assembleSuiteRuns(
        sharedSpaceId,
        workspaceId,
        suiteRunIds,
        topology,
        runsById(childRuns),
        tolerateMissingSuites);
  }

  private SuitePollPlan preFetchSuitePoll(
      String sharedSpaceId,
      String workspaceId,
      List<String> suiteRunIds,
      boolean tolerateMissingSuites)
      throws IOException, InterruptedException {
    Map<String, OctaneSuiteTopologyCache.Topology> topology =
        OctaneSuiteTopologyCache.getAll(
            topologyNamespace(sharedSpaceId, workspaceId, tolerateMissingSuites),
            suiteRunIds,
            missing ->
                fetchSuiteTopologies(sharedSpaceId, workspaceId, missing, tolerateMissingSuites));
    topology = resolveCachedSuiteOwners(sharedSpaceId, workspaceId, suiteRunIds, topology);
    List<String> childRunIds = childRunIds(suiteRunIds, topology);
    return new SuitePollPlan(topology, childRunIds);
  }

  private String topologyNamespace(
      String sharedSpaceId, String workspaceId, boolean tolerateMissingSuites) {
    return baseUrl
        + "\u0000"
        + clientId
        + "\u0000"
        + sharedSpaceId
        + "\u0000"
        + workspaceId
        + (tolerateMissingSuites ? "\u0000available" : "\u0000strict");
  }

  private List<String> childRunIds(
      List<String> suiteRunIds, Map<String, OctaneSuiteTopologyCache.Topology> topology) {
    LinkedHashSet<String> childRunIds = new LinkedHashSet<>();
    for (String suiteRunId : suiteRunIds) {
      childRunIds.addAll(
          topology.getOrDefault(suiteRunId, OctaneSuiteTopologyCache.Topology.empty()).runIds());
    }
    return new ArrayList<>(childRunIds);
  }

  private Map<String, OctaneSuiteTopologyCache.Topology> resolveCachedSuiteOwners(
      String sharedSpaceId,
      String workspaceId,
      List<String> suiteRunIds,
      Map<String, OctaneSuiteTopologyCache.Topology> topology)
      throws InterruptedException {
    Map<String, OctaneSuiteTopologyCache.Topology> resolved = new LinkedHashMap<>(topology);
    for (String suiteRunId : suiteRunIds) {
      OctaneSuiteTopologyCache.Topology current = resolved.get(suiteRunId);
      if (current == null || !Util.isBlank(current.suiteOwnerName())) {
        continue;
      }
      String owner =
          lockedSuiteOwners.get(suiteAssignmentKey(sharedSpaceId, workspaceId, suiteRunId));
      if (Util.isBlank(owner)) {
        owner =
            fetchDedicatedSuiteOwnerName(
                sharedSpaceId,
                workspaceId,
                suiteRunId,
                current.testSuiteId(),
                current.fallbackOwnerName());
      }
      if (!Util.isBlank(owner)) {
        resolved.put(
            suiteRunId,
            new OctaneSuiteTopologyCache.Topology(
                current.runIds(), owner, current.testSuiteId(), current.fallbackOwnerName()));
      }
    }
    return resolved;
  }

  private Map<String, RunRecord> runsById(List<RunRecord> childRuns) {
    Map<String, RunRecord> runsById = new LinkedHashMap<>();
    for (RunRecord run : childRuns) {
      runsById.putIfAbsent(run.getId(), run);
    }
    return runsById;
  }

  private Map<String, List<RunRecord>> assembleSuiteRuns(
      String sharedSpaceId,
      String workspaceId,
      List<String> suiteRunIds,
      Map<String, OctaneSuiteTopologyCache.Topology> topology,
      Map<String, RunRecord> runsById,
      boolean tolerateMissingSuites) {
    Map<String, List<RunRecord>> result = new LinkedHashMap<>();
    for (String suiteRunId : suiteRunIds) {
      OctaneSuiteTopologyCache.Topology suiteTopology =
          topology.getOrDefault(suiteRunId, OctaneSuiteTopologyCache.Topology.empty());
      List<String> topologyRunIds = suiteTopology.runIds();
      // Existing suites always retain their own run ID when no child runs are present. An empty
      // topology therefore identifies a suite omitted by tolerant missing-suite resolution.
      if (tolerateMissingSuites && topologyRunIds.isEmpty()) {
        continue;
      }
      List<RunRecord> runs = new ArrayList<>();
      for (String runId : topologyRunIds) {
        RunRecord run = runsById.get(runId);
        if (run != null) {
          runs.add(run);
        }
      }
      if (tolerateMissingSuites && runs.isEmpty()) {
        continue;
      }
      result.put(
          suiteRunId,
          attributeSuiteRuns(
              suiteAssignmentKey(sharedSpaceId, workspaceId, suiteRunId),
              suiteRunId,
              suiteTopology.suiteOwnerName(),
              runs));
    }
    return result;
  }

  public List<RunRecord> fetchScopedRuns(
      String sharedSpaceId, String workspaceId, List<String> runIds, String scopeQuery)
      throws IOException, InterruptedException {
    if (runIds.isEmpty()) {
      return List.of();
    }
    return fetchRunsByIds(sharedSpaceId, workspaceId, runIds, scopeQuery);
  }

  public List<String> fetchSuiteChildRunIds(
      String sharedSpaceId, String workspaceId, String suiteRunId)
      throws IOException, InterruptedException {
    JsonNode suiteRun = fetchSuiteRun(sharedSpaceId, workspaceId, suiteRunId);
    List<String> runIds = parseRunsInSuite(suiteRun);
    if (runIds.isEmpty()) {
      return List.of(suiteRun.path("id").asString(suiteRunId));
    }
    return runIds;
  }

  public List<DefectRecord> fetchLinkedDefects(
      String sharedSpaceId,
      String workspaceId,
      Map<String, List<RunRecord>> suiteRuns,
      String defectQuery,
      int maxDefects)
      throws IOException, InterruptedException {
    if (suiteRuns.isEmpty() || maxDefects <= 0) {
      return List.of();
    }

    LinkedHashSet<String> runIds = collectRunIds(suiteRuns);
    LinkedHashSet<String> testIds = collectTestIds(suiteRuns);
    if (runIds.isEmpty() && testIds.isEmpty()) {
      return List.of();
    }

    Map<String, DefectRecord> recordsById = new LinkedHashMap<>();
    fetchDefectsForRelation(
        sharedSpaceId, workspaceId, "test", testIds, defectQuery, maxDefects, recordsById);
    fetchDefectsForRelation(
        sharedSpaceId, workspaceId, "run", runIds, defectQuery, maxDefects, recordsById);
    fetchDefectsForRelation(
        sharedSpaceId,
        workspaceId,
        "detected_in_run",
        runIds,
        defectQuery,
        maxDefects,
        recordsById);
    fetchDefectsForRelation(
        sharedSpaceId, workspaceId, "owner_test", testIds, defectQuery, maxDefects, recordsById);
    return new ArrayList<>(recordsById.values());
  }

  public List<DefectRecord> fetchDefectsByIds(
      String sharedSpaceId, String workspaceId, List<String> defectIds, int maxDefects)
      throws IOException, InterruptedException {
    if (defectIds.isEmpty() || maxDefects <= 0) {
      return List.of();
    }
    List<String> clauses = new ArrayList<>();
    for (String defectId : defectIds) {
      if (!Util.isBlank(defectId)) {
        clauses.add("id EQ " + safeEntityId(defectId));
      }
    }
    if (clauses.isEmpty()) {
      return List.of();
    }
    List<DefectRecord> records = new ArrayList<>();
    for (int start = 0;
        start < clauses.size() && records.size() < maxDefects;
        start += QUERY_CHUNK_SIZE) {
      int end = Math.min(start + QUERY_CHUNK_SIZE, clauses.size());
      records.addAll(
          fetchDefectsChunk(
              sharedSpaceId,
              workspaceId,
              clauses.subList(start, end),
              "",
              maxDefects - records.size()));
    }
    return records;
  }

  public int testWorkspaceAccess(String sharedSpaceId, String workspaceId)
      throws IOException, InterruptedException {
    String path =
        workspacePath(sharedSpaceId, workspaceId)
            + "/runs?"
            + parameter("fields", "id")
            + "&"
            + parameter("limit", "1");
    HttpResponse<Void> response =
        send(requestBuilder(baseUrl + path).GET().build(), HttpResponse.BodyHandlers.discarding());
    rememberCookies(response);
    return response.statusCode();
  }

  @Override
  public void close() throws IOException {
    if (cookieHeader == null) {
      return;
    }

    HttpRequest request =
        requestBuilder(baseUrl + "/authentication/sign_out")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    try {
      send(request, HttpResponse.BodyHandlers.discarding());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while signing out of ALM Octane.", e);
    } finally {
      cookieHeader = null;
    }
  }

  private JsonNode fetchSuiteRun(String sharedSpaceId, String workspaceId, String suiteRunId)
      throws IOException, InterruptedException {
    String query = "id EQ " + safeEntityId(suiteRunId);
    SuiteRunLookup runsLookup = findSuiteRunInRuns(sharedSpaceId, workspaceId, suiteRunId, query);
    if (runsLookup.node() != null) {
      return runsLookup.node();
    }
    SuiteRunLookup suiteLookup = findSuiteRunEntity(sharedSpaceId, workspaceId, suiteRunId);
    if (suiteLookup.node() != null) {
      return suiteLookup.node();
    }
    if (runsLookup.failure() != null && suiteLookup.failure() != null) {
      throw new AbortException(
          "ALM Octane suite run lookup failed. Runs collection lookup failed: "
              + runsLookup.failure().getMessage()
              + ". suite_runs fallback failed: "
              + suiteLookup.failure().getMessage());
    }
    throw new AbortException(suiteRunNotFoundMessage(sharedSpaceId, workspaceId, suiteRunId));
  }

  private SuiteRunLookup findSuiteRunInRuns(
      String sharedSpaceId, String workspaceId, String suiteRunId, String query)
      throws InterruptedException {
    Exception lookupFailure = null;
    for (String fields : preferredFirst(suiteRunFieldCandidates(), preferredRunSuiteFields)) {
      String path =
          workspacePath(sharedSpaceId, workspaceId)
              + "/runs?"
              + parameter("query", quote(query))
              + "&"
              + parameter("fields", fields)
              + "&"
              + parameter("limit", "1");
      try {
        JsonNode collection = getJson(path);
        preferredRunSuiteFields = fields;
        JsonNode data = collection.path("data");
        if (data.isArray() && !data.isEmpty()) {
          JsonNode candidate = data.get(0);
          if (suiteRunId.equals(candidate.path("id").asString())) {
            return new SuiteRunLookup(candidate, null);
          }
        }
      } catch (IOException | JacksonException e) {
        // Some Octane versions reject owner or aggregate suite-run fields.
        lookupFailure = e;
      }
    }
    return new SuiteRunLookup(null, lookupFailure);
  }

  private SuiteRunLookup findSuiteRunEntity(
      String sharedSpaceId, String workspaceId, String suiteRunId)
      throws IOException, InterruptedException {
    IOException suiteLookupFailure = null;
    for (String fields : preferredFirst(suiteRunFieldCandidates(), preferredSuiteEntityFields)) {
      String fallbackPath =
          workspacePath(sharedSpaceId, workspaceId)
              + "/suite_runs/"
              + encode(suiteRunId)
              + "?"
              + parameter("fields", fields);
      JsonNode node;
      try {
        node = getJson(fallbackPath);
        preferredSuiteEntityFields = fields;
      } catch (IOException e) {
        if (isNotFound(e)) {
          throw new AbortException(suiteRunNotFoundMessage(sharedSpaceId, workspaceId, suiteRunId));
        }
        suiteLookupFailure = e;
        continue;
      }
      JsonNode data = node.path("data");
      if (data.isArray() && !data.isEmpty()) {
        return new SuiteRunLookup(data.get(0), null);
      }
      if (!node.path("id").isMissingNode()) {
        return new SuiteRunLookup(node, null);
      }
    }
    return new SuiteRunLookup(null, suiteLookupFailure);
  }

  private Map<String, OctaneSuiteTopologyCache.Topology> fetchSuiteTopologies(
      String sharedSpaceId,
      String workspaceId,
      List<String> suiteRunIds,
      boolean tolerateMissingSuites)
      throws IOException, InterruptedException {
    Map<String, OctaneSuiteTopologyCache.Topology> topology = new LinkedHashMap<>();
    for (int start = 0; start < suiteRunIds.size(); start += QUERY_CHUNK_SIZE) {
      int end = Math.min(start + QUERY_CHUNK_SIZE, suiteRunIds.size());
      List<String> chunk = suiteRunIds.subList(start, end);
      fetchRunTopologies(sharedSpaceId, workspaceId, chunk, topology);
      resolveMissingTopologies(sharedSpaceId, workspaceId, chunk, topology, tolerateMissingSuites);
    }
    return topology;
  }

  private void fetchRunTopologies(
      String sharedSpaceId,
      String workspaceId,
      List<String> suiteRunIds,
      Map<String, OctaneSuiteTopologyCache.Topology> topology)
      throws InterruptedException {
    for (String fields : preferredFirst(suiteRunFieldCandidates(), preferredRunSuiteFields)) {
      try {
        JsonNode data =
            getJson(suiteRunsPath(sharedSpaceId, workspaceId, suiteRunIds, fields)).path("data");
        preferredRunSuiteFields = fields;
        addRunTopologies(suiteRunIds, data, topology);
        return;
      } catch (IOException ignored) {
        // Retry without owner before falling back to the suite_runs entity endpoint.
      }
    }
  }

  private String suiteRunsPath(
      String sharedSpaceId, String workspaceId, List<String> suiteRunIds, String fields) {
    return workspacePath(sharedSpaceId, workspaceId)
        + "/runs?"
        + parameter("query", quote(buildIdQuery(suiteRunIds)))
        + "&"
        + parameter("fields", fields)
        + "&"
        + parameter("limit", Integer.toString(PAGE_SIZE));
  }

  private List<String> suiteRunFieldCandidates() {
    return List.of(
        DEFAULT_RUN_BY_SUITE_RUN_FIELDS,
        ASSIGNED_TO_SUITE_RUN_FIELDS,
        ASSIGNEE_SUITE_RUN_FIELDS,
        SAFE_DEFAULT_RUN_BY_SUITE_RUN_FIELDS,
        SAFE_ASSIGNED_TO_SUITE_RUN_FIELDS,
        SAFE_ASSIGNEE_SUITE_RUN_FIELDS,
        SUITE_RUN_FIELDS,
        SAFE_SUITE_RUN_FIELDS,
        RUN_FIELDS,
        SAFE_RUN_FIELDS);
  }

  private List<String> preferredFirst(List<String> candidates, String preferred) {
    if (Util.isBlank(preferred) || preferred.equals(candidates.get(0))) {
      return candidates;
    }
    List<String> ordered = new ArrayList<>(candidates.size());
    if (candidates.contains(preferred)) {
      ordered.add(preferred);
    }
    for (String candidate : candidates) {
      if (!candidate.equals(preferred)) {
        ordered.add(candidate);
      }
    }
    return ordered;
  }

  private void addRunTopologies(
      List<String> suiteRunIds,
      JsonNode data,
      Map<String, OctaneSuiteTopologyCache.Topology> topology) {
    if (!data.isArray()) {
      return;
    }
    for (JsonNode suiteRun : data) {
      String id = suiteRun.path("id").asString();
      if (suiteRunIds.contains(id)) {
        topology.put(id, topologyFromSuiteRun(suiteRun, id));
      }
    }
  }

  private void resolveMissingTopologies(
      String sharedSpaceId,
      String workspaceId,
      List<String> suiteRunIds,
      Map<String, OctaneSuiteTopologyCache.Topology> topology,
      boolean tolerateMissingSuites)
      throws IOException, InterruptedException {
    for (String suiteRunId : suiteRunIds) {
      if (topology.containsKey(suiteRunId)) {
        continue;
      }
      JsonNode suiteRun =
          fetchMissingSuiteRun(sharedSpaceId, workspaceId, suiteRunId, tolerateMissingSuites);
      if (suiteRun != null) {
        topology.put(suiteRunId, topologyFromSuiteRun(suiteRun, suiteRunId));
      }
    }
  }

  private JsonNode fetchMissingSuiteRun(
      String sharedSpaceId, String workspaceId, String suiteRunId, boolean tolerateMissingSuites)
      throws IOException, InterruptedException {
    try {
      return fetchSuiteRun(sharedSpaceId, workspaceId, suiteRunId);
    } catch (AbortException e) {
      if (tolerateMissingSuites && isMissingSuiteRun(e)) {
        return null;
      }
      throw e;
    }
  }

  private boolean isNotFound(IOException exception) {
    return exception != null && Util.trimToEmpty(exception.getMessage()).contains("HTTP 404");
  }

  private boolean isMissingSuiteRun(AbortException exception) {
    String message = Util.trimToEmpty(exception.getMessage());
    return message.startsWith("ALM Octane suite run ") && message.contains(" was not found ");
  }

  private String suiteRunNotFoundMessage(
      String sharedSpaceId, String workspaceId, String suiteRunId) {
    return "ALM Octane suite run "
        + Util.trimToEmpty(suiteRunId)
        + " was not found in shared space "
        + Util.trimToEmpty(sharedSpaceId)
        + " / workspace "
        + Util.trimToEmpty(workspaceId)
        + ". Check that the job's shared space ID and workspace ID match the Octane workspace "
        + "that owns this suite run.";
  }

  private List<RunRecord> fetchRunsByIds(
      String sharedSpaceId, String workspaceId, List<String> runIds, String scopeQuery)
      throws IOException, InterruptedException {
    if (runIds.isEmpty()) {
      return List.of();
    }
    List<List<String>> chunks = new ArrayList<>();
    for (int start = 0; start < runIds.size(); start += QUERY_CHUNK_SIZE) {
      int end = Math.min(start + QUERY_CHUNK_SIZE, runIds.size());
      chunks.add(List.copyOf(runIds.subList(start, end)));
    }

    List<Future<List<RunRecord>>> futures = new ArrayList<>(chunks.size());
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (List<String> chunk : chunks) {
        futures.add(
            executor.submit(() -> fetchRunsChunk(sharedSpaceId, workspaceId, chunk, scopeQuery)));
      }
      List<RunRecord> records = new ArrayList<>(runIds.size());
      try {
        for (Future<List<RunRecord>> future : futures) {
          records.addAll(future.get());
        }
      } catch (ExecutionException e) {
        futures.forEach(future -> future.cancel(true));
        throw mapParallelFetchFailure(e.getCause());
      }
      return records;
    }
  }

  private IOException mapParallelFetchFailure(Throwable failure) throws InterruptedException {
    if (failure instanceof InterruptedException interruptedException) {
      throw interruptedException;
    }
    if (failure instanceof IOException ioException) {
      return ioException;
    }
    if (failure instanceof RuntimeException runtimeException) {
      throw runtimeException;
    }
    if (failure instanceof Error error) {
      throw error;
    }
    return new IOException("Parallel ALM Octane run polling failed.", failure);
  }

  private List<RunRecord> fetchRunsChunk(
      String sharedSpaceId, String workspaceId, List<String> runIds, String scopeQuery)
      throws IOException, InterruptedException {
    if (Util.isBlank(preferredChildRunFields)) {
      synchronized (childRunFieldProfileLock) {
        if (Util.isBlank(preferredChildRunFields)) {
          return fetchRunsChunkWithFieldFallback(sharedSpaceId, workspaceId, runIds, scopeQuery);
        }
      }
    }
    return fetchRunsChunkWithFieldFallback(sharedSpaceId, workspaceId, runIds, scopeQuery);
  }

  private List<RunRecord> fetchRunsChunkWithFieldFallback(
      String sharedSpaceId, String workspaceId, List<String> runIds, String scopeQuery)
      throws IOException, InterruptedException {
    IOException failure = null;
    for (String fields :
        preferredFirst(List.of(CHILD_RUN_FIELDS, SAFE_CHILD_RUN_FIELDS), preferredChildRunFields)) {
      try {
        List<RunRecord> records =
            fetchRunsChunk(sharedSpaceId, workspaceId, runIds, scopeQuery, fields);
        preferredChildRunFields = fields;
        return records;
      } catch (IOException e) {
        failure = e;
      }
    }
    if (failure == null) {
      throw new IOException("No ALM Octane child-run field profile is configured.");
    }
    throw failure;
  }

  private List<RunRecord> fetchRunsChunk(
      String sharedSpaceId,
      String workspaceId,
      List<String> runIds,
      String scopeQuery,
      String fields)
      throws IOException, InterruptedException {
    String query = buildIdQuery(runIds);
    if (!Util.isBlank(scopeQuery)) {
      query = "(" + query + ");(" + scopeQuery + ")";
    }

    Set<String> requestedIds = new LinkedHashSet<>(runIds);
    Map<String, RunRecord> recordsById = new LinkedHashMap<>();
    int offset = 0;
    while (recordsById.size() < requestedIds.size()) {
      String path =
          workspacePath(sharedSpaceId, workspaceId)
              + "/runs?"
              + parameter("query", quote(query))
              + "&"
              + parameter("fields", fields)
              + "&"
              + parameter("limit", Integer.toString(PAGE_SIZE))
              + "&"
              + parameter("offset", Integer.toString(offset));
      JsonNode collection = getJson(path);
      JsonNode data = collection.path("data");
      if (!data.isArray() || data.isEmpty()) {
        break;
      }
      int previousSize = recordsById.size();
      for (JsonNode node : data) {
        RunRecord record = parseRun(node);
        if (requestedIds.contains(record.getId())) {
          recordsById.putIfAbsent(record.getId(), record);
        }
      }
      if (data.size() < PAGE_SIZE || recordsById.size() == previousSize) {
        break;
      }
      offset += PAGE_SIZE;
    }
    return new ArrayList<>(recordsById.values());
  }

  private String buildIdQuery(List<String> runIds) {
    List<String> clauses = new ArrayList<>();
    for (String runId : runIds) {
      clauses.add("id EQ " + safeEntityId(runId));
    }
    return String.join("||", clauses);
  }

  private LinkedHashSet<String> collectRunIds(Map<String, List<RunRecord>> suiteRuns) {
    LinkedHashSet<String> runIds = new LinkedHashSet<>();
    for (List<RunRecord> runs : suiteRuns.values()) {
      for (RunRecord run : runs) {
        if (!Util.isBlank(run.getId())) {
          runIds.add(run.getId());
        }
      }
    }
    return runIds;
  }

  private LinkedHashSet<String> collectTestIds(Map<String, List<RunRecord>> suiteRuns) {
    LinkedHashSet<String> testIds = new LinkedHashSet<>();
    for (List<RunRecord> runs : suiteRuns.values()) {
      for (RunRecord run : runs) {
        if (!Util.isBlank(run.getTestId())) {
          testIds.add(run.getTestId());
        }
      }
    }
    return testIds;
  }

  private void fetchDefectsForRelation(
      String sharedSpaceId,
      String workspaceId,
      String relationField,
      Set<String> relatedIds,
      String defectQuery,
      int maxDefects,
      Map<String, DefectRecord> recordsById)
      throws IOException, InterruptedException {
    if (relatedIds.isEmpty() || recordsById.size() >= maxDefects) {
      return;
    }
    List<String> clauses = buildRelationClauses(relationField, relatedIds);
    try {
      for (int start = 0;
          start < clauses.size() && recordsById.size() < maxDefects;
          start += QUERY_CHUNK_SIZE) {
        int end = Math.min(start + QUERY_CHUNK_SIZE, clauses.size());
        List<DefectRecord> records =
            fetchDefectsChunk(
                sharedSpaceId,
                workspaceId,
                clauses.subList(start, end),
                defectQuery,
                maxDefects - recordsById.size());
        for (DefectRecord record : records) {
          recordsById.putIfAbsent(record.getId(), record);
        }
      }
    } catch (IOException e) {
      if (!isUnknownFieldFailure(e)) {
        throw e;
      }
      // Octane schemas differ by version. Ignore only relationships the server explicitly
      // reports as unknown; transport and server failures must not become zero-defect results.
    }
  }

  private List<String> buildRelationClauses(String relationField, Set<String> relatedIds) {
    List<String> clauses = new ArrayList<>();
    for (String relatedId : relatedIds) {
      clauses.add(relationField + " EQ {id EQ " + safeEntityId(relatedId) + "}");
    }
    return clauses;
  }

  private List<DefectRecord> fetchDefectsChunk(
      String sharedSpaceId,
      String workspaceId,
      List<String> clauses,
      String defectQuery,
      int maxDefects)
      throws IOException, InterruptedException {
    String query = String.join("||", clauses);
    if (!Util.isBlank(defectQuery)) {
      query = "(" + query + ");(" + defectQuery + ")";
    }

    List<DefectRecord> records = new ArrayList<>();
    int offset = 0;
    while (records.size() < maxDefects) {
      int limit = Math.min(PAGE_SIZE, maxDefects - records.size());
      JsonNode collection = getDefectsJson(sharedSpaceId, workspaceId, query, limit, offset);
      JsonNode data = collection.path("data");
      if (!data.isArray() || data.isEmpty()) {
        break;
      }
      for (JsonNode node : data) {
        records.add(parseDefect(node));
      }
      if (data.size() < limit) {
        break;
      }
      offset += limit;
    }
    return records;
  }

  private JsonNode getDefectsJson(
      String sharedSpaceId, String workspaceId, String query, int limit, int offset)
      throws IOException, InterruptedException {
    IOException failure = null;
    for (String fields :
        preferredFirst(
            List.of(EXTENDED_DEFECT_FIELDS, DEFECT_FIELDS, MINIMAL_DEFECT_FIELDS),
            preferredDefectFields)) {
      try {
        JsonNode defects =
            getJson(defectsPath(sharedSpaceId, workspaceId, query, fields, limit, offset));
        preferredDefectFields = fields;
        return defects;
      } catch (IOException e) {
        if (!isUnknownFieldFailure(e)) {
          throw e;
        }
        failure = e;
      }
    }
    if (failure == null) {
      throw new IOException("No ALM Octane defect field profile is configured.");
    }
    throw failure;
  }

  private String defectsPath(
      String sharedSpaceId,
      String workspaceId,
      String query,
      String fields,
      int limit,
      int offset) {
    return workspacePath(sharedSpaceId, workspaceId)
        + "/defects?"
        + parameter("query", quote(query))
        + "&"
        + parameter("fields", fields)
        + "&"
        + parameter("limit", Integer.toString(limit))
        + "&"
        + parameter("offset", Integer.toString(offset));
  }

  private boolean isUnknownFieldFailure(IOException exception) {
    String message = exception.getMessage();
    return message != null && message.contains("platform.unknown_field");
  }

  private JsonNode getJson(String path) throws IOException, InterruptedException {
    StringResponse response = sendWithRetry(() -> requestBuilder(baseUrl + path).GET().build());
    try {
      return objectMapper.readTree(response.body());
    } catch (JacksonException e) {
      // Parser exception messages can quote remote content, including reflected credentials.
      throw new IOException(
          "ALM Octane returned malformed JSON for "
              + response.request().uri()
              + responseBodyMessage(response.body()));
    }
  }

  private StringResponse sendWithRetry(RequestFactory requestFactory)
      throws IOException, InterruptedException {
    IOException lastException = null;
    HttpRequest lastRequest = null;
    StringResponse lastResponse = null;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      HttpRequest request = requestFactory.create();
      lastRequest = request;
      StringResponse response;
      try {
        response = sendForString(request);
      } catch (IOException e) {
        lastException = e;
        pauseBeforeRetry(attempt, null);
        continue;
      }
      rememberCookies(response.headers());
      switch (responseAction(response, attempt)) {
        case REAUTHENTICATE -> authenticate();
        case RETRY -> {
          lastResponse = response;
          pauseBeforeRetry(attempt, response);
        }
        case FAIL -> throw requestFailure(request, response);
        case RETURN -> {
          return response;
        }
      }
    }
    throw failedAfterRetries(lastRequest, lastResponse, lastException);
  }

  private ResponseAction responseAction(StringResponse response, int attempt) {
    if (response.statusCode() == 401 && attempt == 1) {
      return ResponseAction.REAUTHENTICATE;
    }
    if (response.statusCode() == 429 || response.statusCode() >= 500) {
      return ResponseAction.RETRY;
    }
    return response.statusCode() >= 400 ? ResponseAction.FAIL : ResponseAction.RETURN;
  }

  private IOException failedAfterRetries(
      HttpRequest lastRequest, StringResponse lastResponse, IOException lastException) {
    if (lastResponse != null) {
      return requestFailure(lastRequest, lastResponse);
    }
    if (lastException != null) {
      return lastException;
    }
    return new AbortException("ALM Octane request failed after retries.");
  }

  private HttpRequest.Builder requestBuilder(String uri) {
    URI requestUri = OctaneServerUrl.requireAllowedRequest(baseUrl, URI.create(uri));
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(requestUri)
            .timeout(Duration.ofSeconds(60))
            .header("Accept", "application/json")
            .header(TECH_PREVIEW_HEADER, "true");
    if (cookieHeader != null) {
      builder.header("Cookie", cookieHeader.getPlainText());
    }
    return builder;
  }

  private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler)
      throws IOException, InterruptedException {
    OctaneServerUrl.requireAllowedRequest(baseUrl, request.uri());
    return OctaneRequestCoordinator.send(baseUrl, httpClient, request, bodyHandler);
  }

  private StringResponse sendForString(HttpRequest request)
      throws IOException, InterruptedException {
    HttpResponse<InputStream> response = send(request, HttpResponse.BodyHandlers.ofInputStream());
    try (InputStream input = response.body()) {
      byte[] bytes = input.readNBytes(MAX_JSON_RESPONSE_BYTES + 1);
      if (bytes.length > MAX_JSON_RESPONSE_BYTES) {
        throw new IOException(
            "ALM Octane response exceeded the "
                + MAX_JSON_RESPONSE_BYTES
                + " byte safety limit for "
                + request.uri()
                + ".");
      }
      return new StringResponse(
          response.statusCode(),
          response.request(),
          response.headers(),
          new String(bytes, StandardCharsets.UTF_8));
    }
  }

  private AbortException requestFailure(HttpRequest request, StringResponse response) {
    return new AbortException(
        "ALM Octane request failed with HTTP "
            + response.statusCode()
            + " for "
            + request.uri()
            + responseBodyMessage(response.body()));
  }

  private String responseBodyMessage(String body) {
    if (body == null || body.isBlank()) {
      return ". Response body: <empty>";
    }
    body = redactValue(body, clientSecret.getPlainText());
    if (cookieHeader != null) {
      for (String cookie : cookieHeader.getPlainText().split(";")) {
        int separator = cookie.indexOf('=');
        if (separator >= 0) {
          body = redactValue(body, cookie.substring(separator + 1).trim());
        }
      }
    }
    String normalized =
        SENSITIVE_RESPONSE_VALUE.matcher(body).replaceAll("$1***$2").replaceAll("\\s+", " ").trim();
    if (normalized.length() > RESPONSE_BODY_LIMIT) {
      normalized = normalized.substring(0, RESPONSE_BODY_LIMIT) + "...";
    }
    return ". Response body: " + normalized;
  }

  private String redactValue(String body, String value) {
    if (value.isEmpty()) {
      return body;
    }
    String quoted = objectMapper.writeValueAsString(value);
    return body.replace(value, "***").replace(quoted.substring(1, quoted.length() - 1), "***");
  }

  private void rememberCookies(HttpResponse<?> response) {
    rememberCookies(response.headers());
  }

  private void rememberCookies(HttpHeaders headers) {
    List<String> cookies = headers.allValues("Set-Cookie");
    if (cookies.isEmpty()) {
      return;
    }
    Set<String> cookiePairs = new LinkedHashSet<>();
    if (cookieHeader != null) {
      for (String existingCookie : cookieHeader.getPlainText().split(";")) {
        String pair = existingCookie.trim();
        if (!pair.isEmpty()) {
          cookiePairs.add(pair);
        }
      }
    }
    for (String cookie : cookies) {
      String pair = cookie.split(";", 2)[0];
      if (!pair.isBlank()) {
        cookiePairs.add(pair);
      }
    }
    if (!cookiePairs.isEmpty()) {
      cookieHeader = Secret.fromString(String.join("; ", cookiePairs));
    }
  }

  private void pauseBeforeRetry(int attempt, StringResponse response) throws InterruptedException {
    if (attempt >= MAX_ATTEMPTS) {
      return;
    }
    long exponentialMillis = Math.min(10_000L, 500L << Math.max(0, attempt - 1));
    long jitterMillis = ThreadLocalRandom.current().nextLong(exponentialMillis / 4L + 1L);
    long retryAfterMillis = retryAfterMillis(response);
    Thread.sleep(Math.max(retryAfterMillis, exponentialMillis + jitterMillis));
  }

  private long retryAfterMillis(StringResponse response) {
    if (response == null) {
      return 0L;
    }
    Optional<String> value = response.headers().firstValue("Retry-After");
    if (value.isEmpty() || value.get().isBlank()) {
      return 0L;
    }
    String retryAfter = value.get().trim();
    try {
      return Math.max(0L, Long.parseLong(retryAfter)) * 1000L;
    } catch (NumberFormatException ignored) {
      try {
        Duration duration =
            Duration.between(
                ZonedDateTime.now(),
                ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME));
        return Math.max(0L, duration.toMillis());
      } catch (DateTimeParseException invalidDate) {
        return 0L;
      }
    }
  }

  private String workspacePath(String sharedSpaceId, String workspaceId) {
    return "/api/shared_spaces/" + encode(sharedSpaceId) + "/workspaces/" + encode(workspaceId);
  }

  private String parameter(String name, String value) {
    return encode(name) + "=" + encode(value);
  }

  private String quote(String value) {
    return "\"" + value + "\"";
  }

  private String octaneStringLiteral(String value) {
    String source = Util.trimToEmpty(value);
    StringBuilder escaped = new StringBuilder(source.length() + 2).append('^');
    for (int index = 0; index < source.length(); index++) {
      char character = source.charAt(index);
      if ("\\^\"'{}()[]?<>".indexOf(character) >= 0) {
        escaped.append('\\');
      }
      escaped.append(character);
    }
    return escaped.append('^').toString();
  }

  private String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private String safeEntityId(String value) {
    String id = Util.trimToEmpty(value);
    if (!SAFE_ENTITY_ID.matcher(id).matches()) {
      throw new IllegalArgumentException("ALM Octane returned an unsafe entity ID.");
    }
    return id;
  }

  private RunRecord parseRun(JsonNode node) {
    String status = readStatus(node.path("native_status"));
    if (status.isEmpty()) {
      status = readStatus(node.path("status"));
    }
    EntityReference test = readEntity(node.path("test"));
    EntityReference project = readFirstEntity(node, List.of("product_areas", "product_area"));
    String executionActorName =
        readPersonField(node.path("run_by"))
            .or(() -> readPersonField(node.path("native_tester")))
            .orElse("");
    return new RunRecord(
        node.path("id").asString(),
        node.path("name").asString(),
        status,
        executionActorName,
        "",
        test.id,
        test.name,
        project.id,
        project.name);
  }

  private OctaneSuiteTopologyCache.Topology topologyFromSuiteRun(
      JsonNode suiteRun, String fallbackId) {
    List<String> runIds = parseRunsInSuite(suiteRun);
    List<String> effectiveRunIds =
        runIds.isEmpty() ? List.of(suiteRun.path("id").asString(fallbackId)) : List.copyOf(runIds);
    return new OctaneSuiteTopologyCache.Topology(
        effectiveRunIds,
        readSuiteAssignmentName(suiteRun),
        readEntity(suiteRun.path("test")).id,
        readPersonField(suiteRun.path("owner")).orElse(""));
  }

  private String readSuiteAssignmentName(JsonNode suiteRun) {
    Optional<String> defaultRunBy = readPersonField(suiteRun.path("default_run_by"));
    if (defaultRunBy.isPresent()) {
      return defaultRunBy.get();
    }

    // Older Octane schemas expose the parent suite run's "Default run by" as run_by. This
    // method only receives parent suite nodes; child run_by is parsed separately as an actor.
    Optional<String> parentRunBy =
        readPersonField(suiteRun.path("run_by"))
            .filter(name -> !SYSTEM_PARENT_RUNNER.matcher(name).find());
    if (parentRunBy.isPresent()) {
      return parentRunBy.get();
    }

    for (String fieldName : List.of("assigned_to", "assignee")) {
      Optional<String> suiteOwner = readPersonField(suiteRun.path(fieldName));
      if (suiteOwner.isPresent()) {
        return suiteOwner.get();
      }
    }
    return "";
  }

  private String readSuiteOwnerName(JsonNode suiteRun) {
    String assignment = readSuiteAssignmentName(suiteRun);
    return Util.isBlank(assignment)
        ? readPersonField(suiteRun.path("owner")).orElse("")
        : assignment;
  }

  private String resolveSuiteOwnerName(
      String sharedSpaceId, String workspaceId, String suiteRunId, JsonNode suiteRun)
      throws InterruptedException {
    String suiteOwnerName = readSuiteAssignmentName(suiteRun);
    return Util.isBlank(suiteOwnerName)
        ? fetchDedicatedSuiteOwnerName(
            sharedSpaceId,
            workspaceId,
            suiteRunId,
            readEntity(suiteRun.path("test")).id,
            readPersonField(suiteRun.path("owner")).orElse(""))
        : suiteOwnerName;
  }

  private String fetchDedicatedSuiteOwnerName(
      String sharedSpaceId,
      String workspaceId,
      String suiteRunId,
      String testSuiteId,
      String knownFallbackOwnerName)
      throws InterruptedException {
    for (String fields :
        List.of(
            DEFAULT_RUN_BY_FIELDS,
            PARENT_RUN_BY_FIELDS,
            SUITE_ASSIGNED_TO_FIELDS,
            SUITE_ASSIGNEE_FIELDS)) {
      String suiteOwnerName = fetchSuiteRunPerson(sharedSpaceId, workspaceId, suiteRunId, fields);
      if (!Util.isBlank(suiteOwnerName)) {
        return suiteOwnerName;
      }
    }

    String plannedDefaultRunBy =
        fetchRelatedTestSuiteDefaultRunBy(sharedSpaceId, workspaceId, suiteRunId, testSuiteId);
    if (!Util.isBlank(plannedDefaultRunBy)) {
      return plannedDefaultRunBy;
    }

    // Generic ownership is intentionally last. It must not mask the suite plan's Default run by.
    if (!Util.isBlank(knownFallbackOwnerName)) {
      return Util.trimToEmpty(knownFallbackOwnerName);
    }
    return fetchSuiteRunPerson(sharedSpaceId, workspaceId, suiteRunId, SUITE_OWNER_FIELDS);
  }

  private String fetchSuiteRunPerson(
      String sharedSpaceId, String workspaceId, String suiteRunId, String fields)
      throws InterruptedException {
    String path =
        workspacePath(sharedSpaceId, workspaceId)
            + "/suite_runs/"
            + encode(safeEntityId(suiteRunId))
            + "?"
            + parameter("fields", fields);
    try {
      return readSuiteOwnerName(singleEntity(getJson(path)));
    } catch (IOException | JacksonException ignored) {
      // Parent assignment field names vary across Octane versions.
      return "";
    }
  }

  private String fetchRelatedTestSuiteDefaultRunBy(
      String sharedSpaceId, String workspaceId, String suiteRunId, String knownTestSuiteId)
      throws InterruptedException {
    String testSuiteId = Util.trimToEmpty(knownTestSuiteId);
    if (testSuiteId.isEmpty()) {
      testSuiteId = fetchSuiteRunTestId(sharedSpaceId, workspaceId, suiteRunId);
    }
    if (testSuiteId.isEmpty()) {
      return "";
    }

    for (String collection : List.of("tests", "test_suites")) {
      for (String fields : List.of(DEFAULT_RUN_BY_FIELDS, PARENT_RUN_BY_FIELDS)) {
        String path =
            workspacePath(sharedSpaceId, workspaceId)
                + "/"
                + collection
                + "/"
                + encode(safeEntityId(testSuiteId))
                + "?"
                + parameter("fields", fields);
        try {
          String defaultRunBy = readSuiteAssignmentName(singleEntity(getJson(path)));
          if (!Util.isBlank(defaultRunBy)) {
            return defaultRunBy;
          }
        } catch (IOException | JacksonException ignored) {
          // Test suites are aggregate tests in some Octane versions and a subtype in others.
        }
      }
    }
    return "";
  }

  private String fetchSuiteRunTestId(String sharedSpaceId, String workspaceId, String suiteRunId)
      throws InterruptedException {
    String safeSuiteRunId = safeEntityId(suiteRunId);
    String path =
        workspacePath(sharedSpaceId, workspaceId)
            + "/suite_runs/"
            + encode(safeSuiteRunId)
            + "?"
            + parameter("fields", SUITE_TEST_FIELDS);
    try {
      return readEntity(singleEntity(getJson(path)).path("test")).id;
    } catch (IOException | JacksonException ignored) {
      return "";
    }
  }

  private JsonNode singleEntity(JsonNode response) {
    JsonNode data = response.path("data");
    return data.isArray() && !data.isEmpty() ? data.get(0) : response;
  }

  private String suiteOwnerDisplayName(String suiteRunId, String ownerName) {
    String owner = Util.trimToEmpty(ownerName);
    return owner.isEmpty() ? "Unassigned (" + Util.trimToEmpty(suiteRunId) + ")" : owner;
  }

  private String suiteAssignmentKey(String sharedSpaceId, String workspaceId, String suiteRunId) {
    return baseUrl
        + "\u0000"
        + clientId
        + "\u0000"
        + Util.trimToEmpty(sharedSpaceId)
        + "\u0000"
        + Util.trimToEmpty(workspaceId)
        + "\u0000"
        + Util.trimToEmpty(suiteRunId);
  }

  private List<RunRecord> attributeSuiteRuns(
      String assignmentKey, String suiteRunId, String suiteOwnerName, List<RunRecord> runs) {
    String assignment = lockedSuiteOwners.get(assignmentKey);
    if (Util.isBlank(assignment) && !Util.isBlank(suiteOwnerName)) {
      assignment = Util.trimToEmpty(suiteOwnerName);
      rememberSuiteOwner(assignmentKey, assignment);
    }
    String groupingName =
        Util.isBlank(assignment) ? suiteOwnerDisplayName(suiteRunId, "") : assignment;
    return runs.stream().map(run -> run.withSuiteOwnerName(groupingName)).toList();
  }

  private void rememberSuiteOwner(String key, String assignment) {
    lockedSuiteOwners.put(key, assignment);
    while (lockedSuiteOwners.size() > MAX_LOCKED_ATTRIBUTIONS) {
      String eldestAssignmentKey = lockedSuiteOwners.keySet().iterator().next();
      lockedSuiteOwners.remove(eldestAssignmentKey);
    }
  }

  private DefectRecord parseDefect(JsonNode node) {
    EntityReference run = readFirstEntity(node, List.of("run", "detected_in_run"));
    EntityReference test = readFirstEntity(node, List.of("test", "owner_test"));
    EntityReference project = readFirstEntity(node, List.of("product_areas", "product_area"));
    return new DefectRecord(
        node.path("id").asString(),
        node.path("name").asString(),
        node.path("description").asString(),
        readStatus(node.path("severity")),
        readStatus(node.path("priority")),
        readStatus(node.path("phase")),
        run.id,
        test.id,
        project.id,
        project.name);
  }

  private String readStatus(JsonNode statusNode) {
    if (statusNode.isMissingNode() || statusNode.isNull()) {
      return "";
    }
    if (statusNode.isString()) {
      return statusNode.asString();
    }
    Optional<String> logicalName = readOptionalText(statusNode, "logical_name");
    if (logicalName.isPresent()) {
      return logicalName.get();
    }
    return readOptionalText(statusNode, "name").orElse("");
  }

  private Optional<String> readOptionalText(JsonNode node, String fieldName) {
    JsonNode value = node.path(fieldName);
    if (value.isMissingNode() || value.isNull()) {
      return Optional.empty();
    }
    return Optional.of(value.asString());
  }

  private EntityReference readFirstEntity(JsonNode node, List<String> fieldNames) {
    for (String fieldName : fieldNames) {
      EntityReference reference = readEntity(node.path(fieldName));
      if (!reference.isEmpty()) {
        return reference;
      }
    }
    return EntityReference.EMPTY;
  }

  private EntityReference readEntity(JsonNode entityNode) {
    if (entityNode.isMissingNode() || entityNode.isNull()) {
      return EntityReference.EMPTY;
    }
    if (entityNode.isArray()) {
      for (JsonNode item : entityNode) {
        EntityReference reference = readEntity(item);
        if (!reference.isEmpty()) {
          return reference;
        }
      }
      return EntityReference.EMPTY;
    }
    if (entityNode.isObject()) {
      JsonNode data = entityNode.path("data");
      if (data.isArray() && !data.isEmpty()) {
        return readEntity(data.get(0));
      }
      String id = readOptionalText(entityNode, "id").orElse("");
      String name = readOptionalText(entityNode, "name").orElse("");
      return new EntityReference(id, name);
    }
    return new EntityReference(entityNode.asString(), entityNode.asString());
  }

  private Optional<String> readPersonField(JsonNode personNode) {
    if (personNode.isMissingNode() || personNode.isNull()) {
      return Optional.empty();
    }
    if (personNode.isString()) {
      return Optional.of(personNode.asString());
    }
    if (personNode.isArray()) {
      return readPersonArray(personNode);
    }
    Optional<String> directValue = readNamedPersonValue(personNode);
    return directValue.isPresent() ? directValue : readPersonField(personNode.path("data"));
  }

  private Optional<String> readPersonArray(JsonNode people) {
    for (JsonNode person : people) {
      Optional<String> value = readPersonField(person);
      if (value.isPresent()) {
        return value;
      }
    }
    return Optional.empty();
  }

  private Optional<String> readNamedPersonValue(JsonNode personNode) {
    for (String fieldName : List.of("name", "full_name", "display_name", "email", "id")) {
      Optional<String> value = readOptionalText(personNode, fieldName);
      if (value.isPresent() && !value.get().isBlank()) {
        return value;
      }
    }
    return Optional.empty();
  }

  private List<String> parseRunsInSuite(JsonNode node) {
    LinkedHashSet<String> runIds = new LinkedHashSet<>();
    collectRunIds(node.path("runs_in_suite"), runIds);
    return new ArrayList<>(runIds);
  }

  private void collectRunIds(JsonNode node, Set<String> runIds) {
    if (node.isMissingNode() || node.isNull()) {
      return;
    }
    if (node.isArray()) {
      for (JsonNode item : node) {
        collectRunIds(item, runIds);
      }
    } else if (node.isObject()) {
      JsonNode id = node.path("id");
      if (!id.isMissingNode() && !id.isNull()) {
        runIds.add(id.asString());
      }
      collectRunIds(node.path("data"), runIds);
      collectRunIds(node.path("run"), runIds);
    } else if (node.isString() || node.isNumber()) {
      runIds.add(node.asString());
    }
  }

  private interface RequestFactory {
    HttpRequest create();
  }

  private record StringResponse(
      int statusCode, HttpRequest request, HttpHeaders headers, String body) {}

  private record SuiteRunLookup(JsonNode node, Exception failure) {}

  private record SuitePollPlan(
      Map<String, OctaneSuiteTopologyCache.Topology> topology, List<String> childRunIds) {
    private SuitePollPlan {
      topology = Map.copyOf(topology);
      childRunIds = List.copyOf(childRunIds);
    }
  }

  private enum ResponseAction {
    REAUTHENTICATE,
    RETRY,
    FAIL,
    RETURN
  }

  private static final class EntityReference {
    private static final EntityReference EMPTY = new EntityReference("", "");

    private final String id;
    private final String name;

    private EntityReference(String id, String name) {
      this.id = Util.trimToEmpty(id);
      this.name = Util.trimToEmpty(name);
    }

    private boolean isEmpty() {
      return id.isEmpty() && name.isEmpty();
    }
  }
}
