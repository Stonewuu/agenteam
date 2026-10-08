package com.stonewu.agenteam.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stonewu.agenteam.support.ApiContractDocument;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommunityApiContractTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void communityContractKeepsSharedReadsAndDoesNotAdvertisePrivateOperations() {
        var document = ApiContractDocument.load(json);
        assertThat(document.at("/info/version").asText()).isEqualTo("0.2.0");
        var paths = document.required("paths");
        assertThat(paths.required("/enterprises").has("post")).isFalse();
        assertThat(paths.required("/enterprises/{enterpriseId}/roles").has("get")).isTrue();
        assertThat(paths.required("/enterprises/{enterpriseId}/roles").has("post")).isFalse();
        assertThat(paths.has("/enterprises/{enterpriseId}/audit")).isFalse();
        assertThat(paths.has("/enterprises/{enterpriseId}/quota-policies")).isFalse();
        assertThat(paths.required("/enterprises/{enterpriseId}/usage").has("get")).isTrue();
        assertThat(document.at("/components/schemas/ResourceGrant/properties/subjectType/enum"))
            .isEqualTo(json.valueToTree(new String[]{"enterprise", "user"}));
        assertThat(document.at("/components/schemas/UsageItem/properties/subjectType/enum"))
            .isEqualTo(json.valueToTree(new String[]{"enterprise"}));
        assertThat(document.at("/components/schemas").has("AuditEvent")).isFalse();
    }

    @Test
    void extensionCannotReplaceExistingOperationsOrUseAnotherVersion() {
        var document = ApiContractDocument.load(json);
        var extension = json.createObjectNode();
        extension.put("formatVersion", 1).put("version", "0.2.0");
        extension.putObject("paths").putObject("/enterprises").putObject("get");
        extension.putObject("schemas");
        extension.putArray("permissions");
        extension.putObject("enumAdditions");
        assertThatThrownBy(() -> ApiContractDocument.merge(document.deepCopy(), extension))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不能覆盖");
        extension.put("version", "0.1.1");
        assertThatThrownBy(() -> ApiContractDocument.merge(document.deepCopy(), extension))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("版本不一致");
    }
}
