package io.github.oryxlabs.oryxos.boot.config;
import io.github.oryxlabs.oryxos.memory.tool.*;
import org.springframework.context.annotation.*;
@Configuration(proxyBeanMethods=false)
public class MemoryToolConfiguration {
    @Bean SaveMemoryTool saveMemoryTool(MemoryConfiguration.SelectedMemory selected){return new SaveMemoryTool(selected.store());}
    @Bean RecallMemoryTool recallMemoryTool(MemoryConfiguration.SelectedMemory selected){return new RecallMemoryTool(selected.store());}
}
