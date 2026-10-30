package br.com.el.branchcontrol;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class PlanStore {
    private record Entry(Models.Plan plan, String owner) {}
    private final Map<String, Entry> plans = new LinkedHashMap<>();

    public synchronized Models.Plan save(Models.Plan plan, Models.Actor actor) {
        plans.entrySet().removeIf(entry -> expired(entry.getValue().plan()));
        while (plans.size() >= 100) plans.remove(plans.keySet().iterator().next());
        plans.put(plan.id(), new Entry(plan, actor.userId()));
        return plan;
    }

    public synchronized Models.Plan get(String id, Models.Actor actor) {
        Entry entry = plans.get(id);
        Models.Plan plan = entry == null ? null : entry.plan();
        if (plan == null || expired(plan)) {
            plans.remove(id);
            throw new AppException(404, "Prévia expirada ou inexistente. Gere a lista novamente.");
        }
        if (!Groups.ADM.equals(actor.group()) && !entry.owner().equals(actor.userId())) {
            throw new AppException(404, "Prévia não encontrada.");
        }
        return plan;
    }

    private boolean expired(Models.Plan plan) {
        return Instant.parse(plan.createdAt()).plus(Duration.ofHours(1)).isBefore(Instant.now());
    }
}
