package com.majstr.backend.service;

import com.majstr.backend.dto.MaterialPrefsRequest;
import com.majstr.backend.dto.MaterialPrefsResponse;
import com.majstr.backend.entity.MasterMaterialPref;
import com.majstr.backend.entity.MaterialPrefKey;
import com.majstr.backend.exception.MaterialPrefValidationException;
import com.majstr.backend.repository.MasterMaterialPrefRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The master's habitual answers, so the calculator stops asking the same question on every object.
 *
 * <p>What may live here is decided by {@link MaterialPrefKey}: a HABIT of the master's, never a
 * property of the object. A room's perimeter has no default worth remembering, and pre-filling it
 * from the previous flat would present another object's number as this master's answer.</p>
 */
@Service
@RequiredArgsConstructor
public class MaterialPrefService {

    private final MasterMaterialPrefRepository prefRepository;

    @Transactional(readOnly = true)
    public MaterialPrefsResponse get(UUID userId) {
        Map<String, String> prefs = new LinkedHashMap<>();
        for (MasterMaterialPref pref : prefRepository.findByUserId(userId)) {
            prefs.put(pref.getPrefKey().name(), pref.getPrefValue());
        }
        return new MaterialPrefsResponse(prefs);
    }

    /** Upsert; a blank value REMOVES the preference — forgetting a habit has to be expressible. */
    @Transactional
    public MaterialPrefsResponse save(UUID userId, MaterialPrefsRequest req) {
        for (Map.Entry<String, String> entry : req.prefs().entrySet()) {
            MaterialPrefKey key = parseKey(entry.getKey());
            String value = entry.getValue() == null ? "" : entry.getValue().trim();
            MasterMaterialPref existing = prefRepository.findByUserIdAndPrefKey(userId, key).orElse(null);
            if (value.isEmpty()) {
                if (existing != null) {
                    prefRepository.delete(existing);
                }
                continue;
            }
            if (value.length() > 100) {
                throw new MaterialPrefValidationException("error.material-pref.value-too-long");
            }
            if (existing == null) {
                prefRepository.save(MasterMaterialPref.builder()
                        .userId(userId)
                        .prefKey(key)
                        .prefValue(value)
                        .build());
            } else {
                existing.setPrefValue(value);
            }
        }
        return get(userId);
    }

    private MaterialPrefKey parseKey(String raw) {
        try {
            return MaterialPrefKey.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new MaterialPrefValidationException("error.material-pref.invalid-key");
        }
    }
}
