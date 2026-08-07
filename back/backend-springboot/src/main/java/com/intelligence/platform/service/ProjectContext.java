package com.intelligence.platform.service;

import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 项目上下文服务（参考 llm_wiki 的数据隔离机制）
 * 每个请求携带当前项目ID，所有数据操作按 project_id 过滤
 */
@Service
public class ProjectContext {

    /**
     * 从请求头获取当前项目ID
     * 前端通过 X-Project-Id 头传递
     * 如果未传递，返回 null（表示全局/未绑定项目）
     */
    private static final ThreadLocal<Long> overrideProjectId = new ThreadLocal<>();

    public void setCurrentProjectId(Long id) {
        if (id == null) {
            overrideProjectId.remove();
        } else {
            overrideProjectId.set(id);
        }
    }

    public void clearCurrentProjectId() {
        overrideProjectId.remove();
    }

    public Long getCurrentProjectId() {
        if (overrideProjectId.get() != null) {
            return overrideProjectId.get();
        }
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;
            HttpServletRequest request = attrs.getRequest();
            String projectId = request.getHeader("X-Project-Id");
            if (projectId == null || projectId.isEmpty()) {
                // 也支持查询参数
                projectId = request.getParameter("projectId");
            }
            if (projectId != null && !projectId.isEmpty()) {
                return Long.parseLong(projectId);
            }
        } catch (Exception ignored) {}
        return null;
    }
}
