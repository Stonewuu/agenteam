package com.stonewu.agenteam.mapper.test.capacity;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Map;

/**
 * 在测试数据库中批量构造关联数据，普通记录另由通用方法写入。
 */
@Mapper
public interface CapacitySeedMapper {
    void createNumbers();

    void createItems();


    int seedMembersAppUser(@Param("values") Map<String, Object> values);

    int seedMembersUserPreference(@Param("values") Map<String, Object> values);

    int seedMembersEnterpriseMember(@Param("values") Map<String, Object> values);

    int seedMembersSysUserRole(@Param("values") Map<String, Object> values);

    int seedResourcesResource(@Param("values") Map<String, Object> values);

    int seedResourcesResourceDraft(@Param("values") Map<String, Object> values);

    int seedResourcesResourceVersion(@Param("values") Map<String, Object> values);

    int seedResourcesResource2(@Param("values") Map<String, Object> values);

    int seedResourcesAgentListing(@Param("values") Map<String, Object> values);

    int seedResourcesAgentHire(@Param("values") Map<String, Object> values);

    int seedResourcesAgentHire2(@Param("values") Map<String, Object> values);

    int seedResourcesResourceGrant(@Param("values") Map<String, Object> values);

    int seedConversationsAgentConversation(@Param("values") Map<String, Object> values);

    int seedConversationsAgentMessage(@Param("values") Map<String, Object> values);

    int seedKnowledgeKnowledgeDocument(@Param("values") Map<String, Object> values);

    int seedKnowledgeKnowledgeChunk(@Param("values") Map<String, Object> values);

    int minimumConversationsPerMember(@Param("enterprise") String enterprise);

    void setBufferPool(@Param("bytes") long bytes);
}
