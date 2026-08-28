"""Shared multilingual encoder with padded 3-class and native 4-class heads."""

from __future__ import annotations

import torch
from torch import nn


class DualHeadRagGuard(nn.Module):
    ANSWERABILITY_TASK_ID = 0
    GROUNDEDNESS_TASK_ID = 1

    def __init__(self, encoder: nn.Module, *, hidden_size: int, dropout: float = 0.1) -> None:
        super().__init__()
        self.encoder = encoder
        self.dropout = nn.Dropout(dropout)
        self.answerability_head = nn.Linear(hidden_size, 3)
        self.groundedness_head = nn.Linear(hidden_size, 4)

    def forward(
        self,
        input_ids: torch.Tensor,
        attention_mask: torch.Tensor,
        task_ids: torch.Tensor,
    ) -> torch.Tensor:
        encoded = self.encoder(input_ids=input_ids, attention_mask=attention_mask)
        hidden = encoded.last_hidden_state
        mask = attention_mask.unsqueeze(-1).to(hidden.dtype)
        pooled = (hidden * mask).sum(dim=1) / mask.sum(dim=1).clamp(min=1.0)
        pooled = self.dropout(pooled)
        answerability_logits = torch.nn.functional.pad(
            self.answerability_head(pooled), (0, 1), value=-10000.0
        )
        groundedness_logits = self.groundedness_head(pooled)
        selector = task_ids.eq(self.GROUNDEDNESS_TASK_ID).unsqueeze(-1)
        return torch.where(selector, groundedness_logits, answerability_logits)
