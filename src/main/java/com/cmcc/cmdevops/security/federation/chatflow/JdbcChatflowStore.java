package com.cmcc.cmdevops.security.federation.chatflow;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import com.cmcc.cmdevops.security.federation.chatflow.FlowModels.App;
import com.cmcc.cmdevops.security.federation.chatflow.FlowModels.AppRelease;
import com.cmcc.cmdevops.security.federation.chatflow.FlowModels.FlowDraft;
import com.cmcc.cmdevops.security.federation.chatflow.FlowModels.FlowVersion;
import com.cmcc.cmdevops.security.federation.store.FederalDataSource;

import jakarta.annotation.PostConstruct;
import tools.jackson.databind.ObjectMapper;

@Component
public class JdbcChatflowStore implements ChatflowStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcChatflowStore(FederalDataSource dataSource, ObjectMapper objectMapper) {
        this.jdbc = dataSource.jdbc();
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void seedBuiltinIfEmpty() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM fed_app WHERE id = ?", Integer.class,
                DefaultFlows.DEFAULT_APP_ID);
        if (count == null || count == 0) {
            long now = System.currentTimeMillis();
            FlowGraph graph = DefaultFlows.builtinAsk();
            saveFlow(new FlowDraft(
                    DefaultFlows.DEFAULT_FLOW_ID, "智能问数", "system", graph, FlowDraft.DRAFT, now, now));
            FlowVersion version = saveFlowVersion(
                    new FlowVersion("fv-builtin-ask-1", DefaultFlows.DEFAULT_FLOW_ID, 1, graph, "system", now));
            AppRelease release = saveAppRelease(new AppRelease(
                    0L, DefaultFlows.DEFAULT_APP_ID, version.id(), graph, "system", now));
            saveApp(new App(
                    DefaultFlows.DEFAULT_APP_ID,
                    "智能问数",
                    "默认问数应用：知识库 → 本体 → 元数据 → SQL 洞察",
                    DefaultFlows.DEFAULT_FLOW_ID,
                    "system",
                    App.PUBLISHED,
                    release.id(),
                    now,
                    now));
            return;
        }
        BuiltinAskGraphMigrator.migrateIfNeeded(this);
    }

    @Override
    public List<FlowDraft> listFlows() {
        return jdbc.query(
                "SELECT id, name, owner, graph_json, status, created_at, updated_at FROM fed_flow ORDER BY updated_at DESC",
                this::flow);
    }

    @Override
    public Optional<FlowDraft> findFlow(String id) {
        List<FlowDraft> rows = jdbc.query(
                "SELECT id, name, owner, graph_json, status, created_at, updated_at FROM fed_flow WHERE id = ?",
                this::flow,
                id);
        return rows.stream().findFirst();
    }

    @Override
    public void saveFlow(FlowDraft flow) {
        int updated = jdbc.update(
                """
                        UPDATE fed_flow SET name = ?, owner = ?, graph_json = ?, status = ?, updated_at = ?
                        WHERE id = ?
                        """,
                flow.name(),
                flow.owner(),
                writeGraph(flow.graph()),
                flow.status(),
                flow.updatedAt(),
                flow.id());
        if (updated == 0) {
            jdbc.update(
                    """
                            INSERT INTO fed_flow (id, name, owner, graph_json, status, created_at, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            """,
                    flow.id(),
                    flow.name(),
                    flow.owner(),
                    writeGraph(flow.graph()),
                    flow.status(),
                    flow.createdAt(),
                    flow.updatedAt());
        }
    }

    @Override
    public FlowVersion saveFlowVersion(FlowVersion version) {
        jdbc.update(
                """
                        INSERT INTO fed_flow_version (id, flow_id, version, graph_json, published_by, published_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                version.id(),
                version.flowId(),
                version.version(),
                writeGraph(version.graph()),
                version.publishedBy(),
                version.publishedAt());
        return version;
    }

    @Override
    public Optional<FlowVersion> findFlowVersion(String id) {
        List<FlowVersion> rows = jdbc.query(
                """
                        SELECT id, flow_id, version, graph_json, published_by, published_at
                        FROM fed_flow_version WHERE id = ?
                        """,
                this::version,
                id);
        return rows.stream().findFirst();
    }

    @Override
    public Optional<FlowVersion> latestFlowVersion(String flowId) {
        List<FlowVersion> rows = jdbc.query(
                """
                        SELECT id, flow_id, version, graph_json, published_by, published_at
                        FROM fed_flow_version WHERE flow_id = ? ORDER BY version DESC LIMIT 1
                        """,
                this::version,
                flowId);
        return rows.stream().findFirst();
    }

    @Override
    public List<App> listApps() {
        return jdbc.query(
                """
                        SELECT id, name, description, flow_id, owner, status, current_release_id, created_at, updated_at
                        FROM fed_app ORDER BY updated_at DESC
                        """,
                this::app);
    }

    @Override
    public Optional<App> findApp(String id) {
        List<App> rows = jdbc.query(
                """
                        SELECT id, name, description, flow_id, owner, status, current_release_id, created_at, updated_at
                        FROM fed_app WHERE id = ?
                        """,
                this::app,
                id);
        return rows.stream().findFirst();
    }

    @Override
    public void saveApp(App app) {
        int updated = jdbc.update(
                """
                        UPDATE fed_app SET name = ?, description = ?, flow_id = ?, owner = ?, status = ?,
                          current_release_id = ?, updated_at = ?
                        WHERE id = ?
                        """,
                app.name(),
                app.description(),
                app.flowId(),
                app.owner(),
                app.status(),
                app.currentReleaseId(),
                app.updatedAt(),
                app.id());
        if (updated == 0) {
            jdbc.update(
                    """
                            INSERT INTO fed_app
                              (id, name, description, flow_id, owner, status, current_release_id, created_at, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    app.id(),
                    app.name(),
                    app.description(),
                    app.flowId(),
                    app.owner(),
                    app.status(),
                    app.currentReleaseId(),
                    app.createdAt(),
                    app.updatedAt());
        }
    }

    @Override
    public AppRelease saveAppRelease(AppRelease release) {
        if (release.id() > 0) {
            jdbc.update(
                    """
                            INSERT INTO fed_app_release (id, app_id, flow_version_id, graph_json, published_by, published_at)
                            VALUES (?, ?, ?, ?, ?, ?)
                            """,
                    release.id(),
                    release.appId(),
                    release.flowVersionId(),
                    writeGraph(release.graph()),
                    release.publishedBy(),
                    release.publishedAt());
            return release;
        }
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    """
                            INSERT INTO fed_app_release (app_id, flow_version_id, graph_json, published_by, published_at)
                            VALUES (?, ?, ?, ?, ?)
                            """,
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, release.appId());
            ps.setString(2, release.flowVersionId());
            ps.setString(3, writeGraph(release.graph()));
            ps.setString(4, release.publishedBy());
            ps.setLong(5, release.publishedAt());
            return ps;
        }, keys);
        Number key = keys.getKey();
        long id = key == null ? 0L : key.longValue();
        return new AppRelease(id, release.appId(), release.flowVersionId(), release.graph(), release.publishedBy(),
                release.publishedAt());
    }

    @Override
    public Optional<AppRelease> findAppRelease(long id) {
        List<AppRelease> rows = jdbc.query(
                """
                        SELECT id, app_id, flow_version_id, graph_json, published_by, published_at
                        FROM fed_app_release WHERE id = ?
                        """,
                this::release,
                id);
        return rows.stream().findFirst();
    }

    @Override
    public Optional<AppRelease> findAppReleaseByApp(String appId) {
        List<AppRelease> rows = jdbc.query(
                """
                        SELECT r.id, r.app_id, r.flow_version_id, r.graph_json, r.published_by, r.published_at
                        FROM fed_app a
                        JOIN fed_app_release r ON r.id = a.current_release_id
                        WHERE a.id = ?
                        """,
                this::release,
                appId);
        return rows.stream().findFirst();
    }

    private FlowDraft flow(ResultSet rs, int rowNum) throws SQLException {
        return new FlowDraft(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("owner"),
                readGraph(rs.getString("graph_json")),
                rs.getString("status"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"));
    }

    private FlowVersion version(ResultSet rs, int rowNum) throws SQLException {
        return new FlowVersion(
                rs.getString("id"),
                rs.getString("flow_id"),
                rs.getInt("version"),
                readGraph(rs.getString("graph_json")),
                rs.getString("published_by"),
                rs.getLong("published_at"));
    }

    private App app(ResultSet rs, int rowNum) throws SQLException {
        long releaseId = rs.getLong("current_release_id");
        return new App(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("flow_id"),
                rs.getString("owner"),
                rs.getString("status"),
                rs.wasNull() ? null : releaseId,
                rs.getLong("created_at"),
                rs.getLong("updated_at"));
    }

    private AppRelease release(ResultSet rs, int rowNum) throws SQLException {
        return new AppRelease(
                rs.getLong("id"),
                rs.getString("app_id"),
                rs.getString("flow_version_id"),
                readGraph(rs.getString("graph_json")),
                rs.getString("published_by"),
                rs.getLong("published_at"));
    }

    private String writeGraph(FlowGraph graph) {
        try {
            return objectMapper.writeValueAsString(graph);
        } catch (RuntimeException e) {
            throw new IllegalStateException("流程 JSON 序列化失败", e);
        }
    }

    private FlowGraph readGraph(String json) {
        try {
            return objectMapper.readValue(json, FlowGraph.class);
        } catch (RuntimeException e) {
            throw new IllegalStateException("流程 JSON 反序列化失败", e);
        }
    }
}
