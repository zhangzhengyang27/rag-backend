package org.example.service;

import org.example.dto.KbChunkHit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF（Reciprocal Rank Fusion）融合工具。
 *
 * <p>将多路召回结果（每路按返回顺序排列即 rank，1-based）融合为单一排序，
 * 公式：score = Σ 1/(k + rank)。相比「向量优先 + 关键词补位」的简单去重，
 * RRF 对两路召回做加权融合，避免单路召回数少时被系统性压制，是大厂混合检索的标准做法。</p>
 *
 * <p>融合键采用 {@code docId#chunkIndex}（与索引元数据一致）；缺失时该候选不参与融合排序。</p>
 */
public final class RrfFuser {

    private RrfFuser() {
    }

    /**
     * 融合多路召回。
     *
     * @param runs   多路召回，每路内部顺序即为 rank（1-based）
     * @param k      RRF 常数，常用 60
     * @param topK   最终返回的候选数
     * @return 按融合分数降序的前 topK 个候选（保留各候选原始内容/元数据）
     */
    public static List<KbChunkHit> fuse(List<List<KbChunkHit>> runs, int k, int topK) {
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, KbChunkHit> best = new LinkedHashMap<>();
        for (List<KbChunkHit> run : runs) {
            int rank = 1;
            for (KbChunkHit hit : run) {
                String id = hitKey(hit);
                if (id != null) {
                    scores.merge(id, 1.0 / (k + rank), Double::sum);
                    best.putIfAbsent(id, hit);
                }
                rank++;
            }
        }
        List<Map.Entry<String, Double>> sorted = new ArrayList<>(scores.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        List<KbChunkHit> result = new ArrayList<>();
        for (int i = 0; i < Math.min(topK, sorted.size()); i++) {
            result.add(best.get(sorted.get(i).getKey()));
        }
        return result;
    }

    private static String hitKey(KbChunkHit hit) {
        if (hit.getDocId() == null || hit.getChunkIndex() == null) {
            return null;
        }
        return hit.getDocId() + "#" + hit.getChunkIndex();
    }
}
