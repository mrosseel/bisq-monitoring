package io.bisq.monitoring;

import lombok.AllArgsConstructor;
import lombok.Data;

/*

 */
@Data
@AllArgsConstructor
public class ProcessResult {
    String result;
    String error;
}
