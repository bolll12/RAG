package com.pingan.rag;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import static com.pingan.rag.RagStore.*;
import static com.pingan.rag.WikiContent.*;

final class WikiStore {
    private final RagStore store;
    WikiStore(RagStore store) { this.store = store; }

    static void initialize(Connection db) throws SQLException {
        update(db, "CREATE TABLE IF NOT EXISTS wiki_state(collection TEXT PRIMARY KEY,source_revision INTEGER NOT NULL DEFAULT 0,published_revision INTEGER NOT NULL DEFAULT -1)");
        update(db, "CREATE TABLE IF NOT EXISTS wiki_pages(id TEXT PRIMARY KEY,collection TEXT NOT NULL,title TEXT NOT NULL,kind TEXT NOT NULL,status TEXT NOT NULL,version INTEGER NOT NULL,content TEXT NOT NULL,updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        update(db, "CREATE INDEX IF NOT EXISTS wiki_pages_collection ON wiki_pages(collection,status)");
        update(db, "CREATE TABLE IF NOT EXISTS wiki_links(from_id TEXT NOT NULL REFERENCES wiki_pages(id),to_id TEXT NOT NULL REFERENCES wiki_pages(id),PRIMARY KEY(from_id,to_id))");
        update(db, "CREATE INDEX IF NOT EXISTS wiki_links_to ON wiki_links(to_id)");
        update(db, "CREATE TABLE IF NOT EXISTS wiki_revisions(page_id TEXT NOT NULL REFERENCES wiki_pages(id),version INTEGER NOT NULL,content TEXT NOT NULL,created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,PRIMARY KEY(page_id,version))");
        update(db, "CREATE TABLE IF NOT EXISTS wiki_cache(id TEXT PRIMARY KEY,content TEXT NOT NULL)");
        update(db, "CREATE TABLE IF NOT EXISTS wiki_jobs(id TEXT PRIMARY KEY,collection TEXT NOT NULL,status TEXT NOT NULL,done INTEGER NOT NULL DEFAULT 0,total INTEGER NOT NULL DEFAULT 0,message TEXT NOT NULL,created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        update(db, "CREATE INDEX IF NOT EXISTS wiki_jobs_collection ON wiki_jobs(collection)");
    }
    static void invalidate(Connection db, String collection) throws SQLException {
        update(db, "INSERT INTO wiki_state(collection,source_revision) VALUES(?,1) ON CONFLICT(collection) DO UPDATE SET source_revision=source_revision+1", collection);
        update(db, "UPDATE wiki_pages SET status='stale' WHERE collection=? AND status='ready'", collection);
    }
    long revision(Connection db, String collection) throws SQLException {
        var state = one(db, "SELECT source_revision FROM wiki_state WHERE collection=?", collection);
        return state == null ? 0 : ((Number) state.get("source_revision")).longValue();
    }
    record Snapshot(long revision, List<Map<String, Object>> documents) {}
    Snapshot snapshot(String collection) {
        // Read the revision and documents from one SQLite snapshot, including during concurrent uploads.
        return store.transaction(db -> new Snapshot(revision(db, collection), query(db,
                "SELECT * FROM documents WHERE collection=? ORDER BY id", collection)));
    }
    String cached(String id) {
        return store.read(db -> { var row = one(db, "SELECT content FROM wiki_cache WHERE id=?", id);
            return row == null ? null : (String) row.get("content"); });
    }
    void cache(String id, String content) { store.read(db -> update(db, "INSERT OR REPLACE INTO wiki_cache VALUES(?,?)", id, content)); }
    void recoverJobs() {
        store.read(db -> update(db, "UPDATE wiki_jobs SET status='failed',message='服务重启中断了任务，请重新生成',updated_at=CURRENT_TIMESTAMP WHERE status IN ('queued','running')"));
    }
    Map<String, Object> newJob(String collection) {
        String id = UUID.randomUUID().toString();
        store.read(db -> update(db, "INSERT INTO wiki_jobs(id,collection,status,message) VALUES(?,?,'queued','等待整理知识')", id, collection));
        return job(id);
    }
    Map<String, Object> job(String id) { return store.read(db -> one(db, "SELECT * FROM wiki_jobs WHERE id=?", id)); }
    void progress(String id, String status, int done, int total, String message) {
        store.read(db -> update(db, "UPDATE wiki_jobs SET status=?,done=?,total=?,message=?,updated_at=CURRENT_TIMESTAMP WHERE id=?", status, done, total, message, id));
    }
    Map<String, Object> status(String collection) {
        return store.read(db -> Json.map("pages", one(db, "SELECT COUNT(*) AS count FROM wiki_pages WHERE collection=? AND status<>'archived'", collection).get("count"),
                "stale", one(db, "SELECT COUNT(*) AS count FROM wiki_pages WHERE collection=? AND status='stale'", collection).get("count"),
                "job", one(db, "SELECT * FROM wiki_jobs WHERE collection=? ORDER BY rowid DESC LIMIT 1", collection),
                "source_revision", revision(db, collection)));
    }
    List<Map<String, Object>> pages(String collection, String search) {
        return store.read(db -> query(db, "SELECT id,title,kind,status,version,updated_at FROM wiki_pages WHERE collection=? AND status<>'archived' AND instr(lower(title),lower(?))>0 ORDER BY kind,title", collection, search));
    }
    Map<String, Object> page(String id, String collection) {
        return store.read(db -> {
            var page = one(db, "SELECT * FROM wiki_pages WHERE id=? AND collection=? AND status<>'archived'", id, collection);
            if (page == null) return null;
            page.put("content", Json.object((String) page.get("content")));
            page.put("links", query(db, "SELECT p.id,p.title,p.status FROM wiki_links l JOIN wiki_pages p ON p.id=l.to_id WHERE l.from_id=? AND p.status<>'archived' ORDER BY p.title", id));
            page.put("backlinks", query(db, "SELECT p.id,p.title,p.status FROM wiki_links l JOIN wiki_pages p ON p.id=l.from_id WHERE l.to_id=? AND p.status<>'archived' ORDER BY p.title", id));
            return page;
        });
    }
    List<Map<String, Object>> readyPages(String collection) {
        return store.read(db -> query(db, "SELECT * FROM wiki_pages WHERE collection=? AND status='ready' AND kind<>'answer'", collection));
    }
    List<Map<String, Object>> revisions(String id, String collection) {
        return store.read(db -> query(db, "SELECT r.version,r.created_at,r.content FROM wiki_revisions r JOIN wiki_pages p ON p.id=r.page_id WHERE p.id=? AND p.collection=? ORDER BY r.version DESC LIMIT 20", id, collection));
    }
    private void save(Connection db, String collection, Page page, String model) throws SQLException {
        String content = Json.write(Json.map("facts", page.facts(), "related_topics", page.links(), "model", model));
        var current = one(db, "SELECT * FROM wiki_pages WHERE id=?", page.id());
        int version = current == null ? 1 : ((Number) current.get("version")).intValue();
        boolean changed = current == null || !content.equals(current.get("content")) || !page.title().equals(current.get("title")) || "archived".equals(current.get("status"));
        if (changed && current != null) version++;
        update(db, "INSERT INTO wiki_pages(id,collection,title,kind,status,version,content) VALUES(?,?,?,?,'ready',?,?) ON CONFLICT(id) DO UPDATE SET title=excluded.title,status='ready',version=excluded.version,content=excluded.content,updated_at=CURRENT_TIMESTAMP",
                page.id(), collection, page.title(), page.kind(), version, content);
        if (changed) update(db, "INSERT INTO wiki_revisions(page_id,version,content) VALUES(?,?,?)", page.id(), version,
                Json.write(Json.map("title", page.title(), "kind", page.kind(), "content", Json.read(content))));
    }
    boolean publish(String collection, Snapshot snapshot, List<Page> pages, String model) {
        return store.transaction(db -> {
            if (revision(db, collection) != snapshot.revision()) return false;
            // Publish the complete derived view atomically; no partial model output becomes searchable.
            var previous = query(db, "SELECT id FROM wiki_pages WHERE collection=? AND kind<>'answer' AND status<>'archived'", collection);
            for (var page : pages) save(db, collection, page, model);
            var retained = pages.stream().map(Page::id).collect(java.util.stream.Collectors.toSet());
            for (var old : previous) if (!retained.contains(old.get("id")))
                update(db, "UPDATE wiki_pages SET status='archived' WHERE id=?", old.get("id"));
            for (var note : query(db, "SELECT * FROM wiki_pages WHERE collection=? AND kind='answer' AND status<>'archived'", collection)) {
                boolean valid = validEvidence(db, (String) note.get("content"), collection);
                update(db, "UPDATE wiki_pages SET status=? WHERE id=?", valid ? "ready" : "stale", note.get("id"));
            }
            rebuildLinks(db, collection);
            update(db, "INSERT INTO wiki_state(collection,source_revision,published_revision) VALUES(?,?,?) ON CONFLICT(collection) DO UPDATE SET published_revision=excluded.published_revision", collection, snapshot.revision(), snapshot.revision());
            return true;
        });
    }
    private boolean validEvidence(Connection db, String content, String collection) throws SQLException {
        for (var fact : Json.read(content).path("facts")) for (var citation : fact.path("citations")) {
            var doc = one(db, "SELECT version FROM documents WHERE id=? AND collection=?", citation.path("document_id").asText(), collection);
            if (doc == null || ((Number) doc.get("version")).intValue() != citation.path("version").asInt()) return false;
        }
        return true;
    }
    boolean saveAnswer(String collection, long revision, Page page, String model) {
        return store.transaction(db -> {
            if (revision(db, collection) != revision) return false;
            save(db, collection, page, model);
            rebuildLinks(db, collection);
            return true;
        });
    }
    private void rebuildLinks(Connection db, String collection) throws SQLException {
        update(db, "DELETE FROM wiki_links WHERE from_id IN (SELECT id FROM wiki_pages WHERE collection=?)", collection);
        var pages = query(db, "SELECT * FROM wiki_pages WHERE collection=? AND status<>'archived'", collection);
        Map<String, String> topics = new HashMap<>();
        Set<String> ids = new HashSet<>();
        for (var page : pages) {
            ids.add((String) page.get("id"));
            if ("topic".equals(page.get("kind"))) topics.put(topicKey((String) page.get("title")), (String) page.get("id"));
        }
        for (var page : pages) {
            String from = (String) page.get("id");
            var content = Json.read((String) page.get("content"));
            Set<String> targets = new HashSet<>();
            for (var topic : content.path("related_topics")) {
                String to = topics.get(topicKey(topic.asText()));
                if (to != null) targets.add(to);
            }
            for (var fact : content.path("facts")) for (var citation : fact.path("citations")) {
                String source = id(collection, "source:" + citation.path("document_id").asText());
                if (ids.contains(source)) targets.add(source);
            }
            for (String to : targets) if (!to.equals(from))
                update(db, "INSERT OR IGNORE INTO wiki_links VALUES(?,?)", from, to);
        }
    }
}
