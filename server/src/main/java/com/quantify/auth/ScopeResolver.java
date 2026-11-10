package com.quantify.auth;

import com.quantify.org.ClassMapper;
import com.quantify.org.SysClass;
import com.quantify.user.SysUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把账号的 scope 解析成一组可见的 class_id。
 *
 * <p>三种范围最终都变成同一个 {@code WHERE class_id IN (...)}，
 * 业务代码里<b>不写 if/else 分支</b>。写成分支的后果不是报错，而是
 * 四十几个班铺开之后必然漏一处，漏了就是跨班数据泄露，而且不崩不报错没人发现。
 *
 * <p><b>缓存带 TTL。</b>不能永久缓存——学院新建了班级之后，
 * 已登录的账号会一直看不到新班级（表现为"新班的班委登录后什么都查不到"）。
 * 这里取 60 秒过期，既省掉绝大部分查询，又能自愈。
 * 明确的变更（改账号范围、加班级）另外调 {@link #evict} / {@link #evictAll} 立即生效。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScopeResolver {

    /** 缓存存活时间（毫秒）。班级结构很少变，但绝不能永久缓存 */
    private static final long TTL_MS = 60_000L;

    private final ClassMapper classMapper;

    private record Entry(List<Long> classIds, long expireAt) {
        boolean expired() {
            return System.currentTimeMillis() > expireAt;
        }
    }

    private final Map<Long, Entry> cache = new ConcurrentHashMap<>();

    public List<Long> resolveClassIds(SysUser user) {
        Entry e = cache.get(user.getId());
        if (e != null && !e.expired()) {
            return e.classIds();
        }
        List<Long> ids = doResolve(user);
        cache.put(user.getId(), new Entry(ids, System.currentTimeMillis() + TTL_MS));
        return ids;
    }

    private List<Long> doResolve(SysUser user) {
        Integer type = user.getScopeType();
        if (type == null) {
            return List.of();
        }
        return switch (type) {
            // 1 = 全院
            case 1 -> classMapper.selectAllEnabledIds();
            // 2 = 本年级
            case 2 -> classMapper.selectIdsByGrade(user.getScopeId());
            // 3 = 本班：班级已停用时返回空列表，避免本班账号继续看到数据
            case 3 -> {
                Long classId = user.getScopeId();
                if (classId == null) {
                    yield List.of();
                }
                SysClass c = classMapper.selectById(classId);
                yield (c != null && Objects.equals(c.getStatus(), 1))
                        ? List.of(classId) : List.of();
            }
            default -> List.of();
        };
    }

    /** 账号的数据范围被改动时调用，立即生效 */
    public void evict(Long userId) {
        cache.remove(userId);
    }

    /** 班级/年级结构变动时调用，立即生效 */
    public void evictAll() {
        cache.clear();
        log.debug("数据范围缓存已清空");
    }
}
