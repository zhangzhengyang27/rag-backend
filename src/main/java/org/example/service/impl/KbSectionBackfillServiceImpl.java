package org.example.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.example.service.KbSectionBackfillService;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;

/**
 * 用一条 GROUP BY 聚合 + 一条 JOIN UPDATE 完成父级回填，避免 27 万行逐条 Java 循环。
 * 关键点：GROUP_CONCAT 需要把 session 上限调大，且必须与 INSERT 用同一个 JDBC 连接；
 * 文本截到 20,000 字，防止极端长章节把单行撑爆。
 */
@Slf4j
@Service
public class KbSectionBackfillServiceImpl implements KbSectionBackfillService {

    private static final int MAX_SECTION_CHARS = 20_000;

    private final JdbcTemplate jdbcTemplate;

    public KbSectionBackfillServiceImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Result backfill() {
        Integer[] counts = jdbcTemplate.execute((ConnectionCallback<Integer[]>) conn -> {
            conn.setAutoCommit(false);
            try {
                try (Statement st = conn.createStatement()) {
                    st.execute("SET SESSION group_concat_max_len = 2000000");
                    st.execute("DELETE FROM kb_section");
                }
                int sections;
                String insertSql = """
                        INSERT INTO kb_section
                          (version, library_id, doc_id, heading_ordinal, heading_path, anchor, text,
                           chunk_count, token_count, created_date, updated_date)
                        SELECT 0, library_id, doc_id, COALESCE(heading_ordinal, 0),
                               MAX(heading_path), MAX(anchor),
                               LEFT(GROUP_CONCAT(text ORDER BY chunk_index SEPARATOR '\\n'), %d),
                               COUNT(*), COALESCE(SUM(token_count), 0), NOW(6), NOW(6)
                        FROM kb_chunk
                        GROUP BY library_id, doc_id, COALESCE(heading_ordinal, 0)
                        """.formatted(MAX_SECTION_CHARS);
                try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                    sections = ps.executeUpdate();
                }
                int chunks;
                try (Statement st = conn.createStatement()) {
                    chunks = st.executeUpdate("""
                            UPDATE kb_chunk c
                            JOIN kb_section s
                              ON c.doc_id = s.doc_id
                             AND COALESCE(c.heading_ordinal, 0) = s.heading_ordinal
                            SET c.parent_id = s.id
                            """);
                }
                conn.commit();
                log.info("父子分块父级回填完成 - sections: {}, chunks: {}", sections, chunks);
                return new Integer[]{sections, chunks};
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        });
        return new Result(counts[0], counts[1]);
    }
}
