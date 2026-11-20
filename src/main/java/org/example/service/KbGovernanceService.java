package org.example.service;

import org.example.vo.KbGovernanceIssueView;
import org.example.vo.KbGovernanceSummaryView;

import java.util.Date;
import java.util.List;

/**
 * 知识治理：对公共库做重复/过期/责任人/空分块体检，并提供责任人维护入口。
 */
public interface KbGovernanceService {

    KbGovernanceSummaryView summary(String libraryId, int staleDays);

    List<KbGovernanceIssueView> issues(String libraryId, String type, int staleDays, int limit);

    /** 更新文档责任人与复核日期；owner 为空表示清空责任人。 */
    void update(String libraryId, Long docId, String owner, Date reviewDueDate, String operator);
}
