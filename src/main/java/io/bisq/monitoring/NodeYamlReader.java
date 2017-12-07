package io.bisq.monitoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.Data;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.builder.ReflectionToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

import java.io.File;

/*

 */
@Slf4j
@Data
public class NodeYamlReader {
    private NodeConfig nodeConfig;

    public NodeYamlReader(String localYamlData) {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

        try {
            this.nodeConfig = mapper.readValue(new File(localYamlData), NodeConfig.class);
            log.debug(ReflectionToStringBuilder.toString(nodeConfig, ToStringStyle.MULTI_LINE_STYLE));
        } catch (Exception e) {
            log.error("Error while reading yaml file", e);
        }
    }
}
