package com.dataagent.lab.domain;

import java.util.List;

public record ClarificationPrompt(String question, List<ClarificationOption> options) {
}
