package com.majstr.backend.dto;

import com.majstr.backend.entity.HelpReason;

/**
 * The home screen's «Допомога з налаштуванням» card (V226): why it stands, and the number to start the field
 * with — the one he shared with the Telegram bot, or null.
 */
public record HelpOfferResponse(HelpReason reason, String phone) {}
