package com.intelligence.platform.controller;

import com.intelligence.platform.entity.Setting;
import com.intelligence.platform.mapper.SettingMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/settings")
@CrossOrigin(origins = "*")
public class SettingController {

    @Autowired
    private SettingMapper settingMapper;

    @GetMapping
    public Map<String, String> getSettings() {
        List<Setting> settings = settingMapper.selectList(null);
        return settings.stream().collect(Collectors.toMap(Setting::getSettingKey, Setting::getValue));
    }

    @PutMapping("/")
    public Map<String, Object> updateSettings(@RequestBody Map<String, String> items) {
        for (Map.Entry<String, String> entry : items.entrySet()) {
            Setting setting = settingMapper.selectById(entry.getKey());
            if (setting != null) {
                setting.setValue(entry.getValue());
                settingMapper.updateById(setting);
            } else {
                setting = new Setting();
                setting.setSettingKey(entry.getKey());
                setting.setValue(entry.getValue());
                settingMapper.insert(setting);
            }
        }
        return Map.of("message", "更新了 " + items.size() + " 项配置");
    }
}
