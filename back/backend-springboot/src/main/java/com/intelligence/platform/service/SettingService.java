package com.intelligence.platform.service;

import com.intelligence.platform.entity.Setting;
import com.intelligence.platform.mapper.SettingMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SettingService {

    @Autowired
    private SettingMapper settingMapper;

    public int getInt(String key, int defaultValue) {
        Setting s = settingMapper.selectById(key);
        if (s != null && s.getValue() != null) {
            try { return Integer.parseInt(s.getValue()); } catch (NumberFormatException e) { return defaultValue; }
        }
        return defaultValue;
    }

    public float getFloat(String key, float defaultValue) {
        Setting s = settingMapper.selectById(key);
        if (s != null && s.getValue() != null) {
            try { return Float.parseFloat(s.getValue()); } catch (NumberFormatException e) { return defaultValue; }
        }
        return defaultValue;
    }
}
