package com.jillesvangurp.springdepend;

import static java.util.stream.Collectors.toMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.support.GenericApplicationContext;

/**
 * Spring dependency analyzer that works with any GenericApplicationContext.
 * <p>
 * Integrate in Spring boot app:
 * 
 * <pre>
 * @SpringBootApplication
 * public class MyApplication {
 *  public static void main(String[] args) {
 *    SpringApplication app = new SpringApplication(MyApplication.class);
 *    app.addListeners(event -> {
 *      if (event instanceof ApplicationStartedEvent) {
 *        SpringDependencyAnalyzer2.printCircles((GenericApplicationContext) ((ApplicationStartedEvent) event).getApplicationContext()); }
 *    });
 *    app.run(args);
 *  }
 * </pre>
 * <p>
 * Integrate in Spring boot test:
 * <pre>
 * &#64;SpringBootTest(classes = {ExampleConfig.class})
 * class ExampleControllerTest {
 *
 *  &#64;Autowired GenericApplicationContext ctx;
 *  @Test
 *  public void dumpCycles() {
 *       SpringDependencyAnalyzer2.printCircles(ctx);
 *  }
 * </pre>
 */
public class SpringDependencyAnalyzer2 {

    public static final int MAX_DEPTH = 20;
    private final GenericApplicationContext context;

    public SpringDependencyAnalyzer2(GenericApplicationContext context) {
        this.context = context;
    }

    public Map<String, Set<String>> getBeanDependencies() {
        Map<String, Set<String>> beanDeps = new TreeMap<>();
        ConfigurableListableBeanFactory factory = context.getBeanFactory();
        for (String beanName : factory.getBeanDefinitionNames()) {
            if (factory.getBeanDefinition(beanName).isAbstract()) {
                continue;
            }
            String[] dependenciesForBean = factory.getDependenciesForBean(beanName);
            Set<String> set = beanDeps.computeIfAbsent(beanName, k -> new TreeSet<>());
            Collections.addAll(set, dependenciesForBean);
        }
        return beanDeps;
    }

    public BeanDependencyStatistic getBeanDependencyStatistic() {
        Map<String, Set<String>> beanDependencies = getBeanDependencies();
        LinkedHashMap<String, BeanDependency> map = new LinkedHashMap<>();
        beanDependencies.forEach((name, dependencies) -> {
            List<String> circularDependencyDescriptions = new LinkedList<>();
            findCycleDependencies(circularDependencyDescriptions, beanDependencies, dependencies, new LinkedHashSet<>(),
                    name, 0, MAX_DEPTH);
            map.put(name, new BeanDependency(dependencies.size(),
                    new ArrayList<>(dependencies),
                    circularDependencyDescriptions.size(),
                    new ArrayList<>(circularDependencyDescriptions)));
        });
        int count = 0;
        for (Map.Entry<String, BeanDependency> stringBeanDependencyEntry : map.entrySet()) {
            count = count + stringBeanDependencyEntry.getValue().getCircularDependencyCount();
        }
        LinkedHashMap<String, BeanDependency> collect = map.entrySet().stream().sorted((o1, o2) -> {
            BeanDependency value1 = o1.getValue();
            BeanDependency value2 = o2.getValue();
            int i = value2.getCircularDependencyCount() - value1.getCircularDependencyCount();
            return i == 0 ? o1.getKey().compareTo(o2.getKey()) : i;
        }).collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (e1, e2) -> e2, LinkedHashMap::new));

        return new BeanDependencyStatistic(count, collect);
    }

    private void findCycleDependencies(
            List<String> circularDependencyDescriptions,
            Map<String, Set<String>> allBeansDependenciesMap,
            Set<String> dependencies,
            Set<String> dependencyNameChain,
            String targetName,
            int currentDepth,
            int maxDepth) {
        currentDepth++;
        int stepDepthValue = currentDepth;
        for (String dep : dependencies) {
            Set<String> currentStepNameChain = new LinkedHashSet<>(dependencyNameChain);
            Set<String> depsOfBeanDep = allBeansDependenciesMap.get(dep);
            if (depsOfBeanDep == null || depsOfBeanDep.isEmpty()) {
                currentStepNameChain.remove(dep);
                continue;
            }
            if (depsOfBeanDep.contains(targetName) && !currentStepNameChain.contains(targetName)) {
                circularDependencyDescriptions.add(targetName);
                circularDependencyDescriptions.addAll(currentStepNameChain);
                circularDependencyDescriptions.add(dep);
                circularDependencyDescriptions.add(targetName);
            }
            if (stepDepthValue + 1 <= maxDepth) {
                currentStepNameChain.add(dep);
                findCycleDependencies(circularDependencyDescriptions, allBeansDependenciesMap, depsOfBeanDep,
                        currentStepNameChain, targetName, stepDepthValue, maxDepth);
            }
        }
    }

    public static void printCircles(GenericApplicationContext context) {
        SpringDependencyAnalyzer2 analyzer = new SpringDependencyAnalyzer2(context);
        BeanDependencyStatistic deps = analyzer.getBeanDependencyStatistic();
        deps.getDependencyMap().forEach((b, d) -> {
            if (d.getCircularDependencyCount() > 0) {
                final List<String> descriptions = d.getCircularDependencyDescriptions();
                System.out.println("== #" + descriptions.size() + " " + b);
                String injects = String.join(", ", d.getInjectedBeanNames());
                String circle = String.join("->", descriptions);
                System.out.println("injected: " + injects);
                System.out.println("circle:   " + circle);
            }
        });
    }

    // --- pojos
    public static class BeanDependency {

        private final int injectedBeanCount;
        private final List<String> injectedBeanNames;
        private final int circularDependencyCount;
        private final List<String> circularDependencyDescriptions;

        @java.beans.ConstructorProperties({ "injectedBeanCount", "injectedBeanNames", "circularDependencyCount",
                "circularDependencyDescriptions" })
        public BeanDependency(int injectedBeanCount, List<String> injectedBeanNames,
                int circularDependencyCount, List<String> circularDependencyDescriptions) {
            this.injectedBeanCount = injectedBeanCount;
            this.injectedBeanNames = injectedBeanNames;
            this.circularDependencyCount = circularDependencyCount;
            this.circularDependencyDescriptions = circularDependencyDescriptions;
        }

        public int getInjectedBeanCount() {
            return this.injectedBeanCount;
        }

        public List<String> getInjectedBeanNames() {
            return this.injectedBeanNames;
        }

        public int getCircularDependencyCount() {
            return this.circularDependencyCount;
        }

        public List<String> getCircularDependencyDescriptions() {
            return this.circularDependencyDescriptions;
        }

        public boolean equals(final Object o) {
            if (o == this) {
                return true;
            }
            if (!(o instanceof BeanDependency)) {
                return false;
            }
            final BeanDependency other = (BeanDependency) o;
            if (!other.canEqual(this)) {
                return false;
            }
            if (this.getInjectedBeanCount() != other.getInjectedBeanCount()) {
                return false;
            }
            final Object this$injectedBeanNames = this.getInjectedBeanNames();
            final Object other$injectedBeanNames = other.getInjectedBeanNames();
            if (!this$injectedBeanNames.equals(other$injectedBeanNames)) {
                return false;
            }
            if (this.getCircularDependencyCount() != other.getCircularDependencyCount()) {
                return false;
            }
            final Object this$circularDependencyDescriptions = this.getCircularDependencyDescriptions();
            final Object other$circularDependencyDescriptions = other.getCircularDependencyDescriptions();
            return this$circularDependencyDescriptions.equals(other$circularDependencyDescriptions);
        }

        protected boolean canEqual(final Object other) {
            return other instanceof BeanDependency;
        }

        public int hashCode() {
            final int PRIME = 59;
            int result = 1;
            result = result * PRIME + this.getInjectedBeanCount();
            final Object $injectedBeanNames = this.getInjectedBeanNames();
            result = result * PRIME + $injectedBeanNames.hashCode();
            result = result * PRIME + this.getCircularDependencyCount();
            final Object $circularDependencyDescriptions = this.getCircularDependencyDescriptions();
            result = result * PRIME + $circularDependencyDescriptions.hashCode();
            return result;
        }

        public String toString() {
            return "SpringDependencyAnalyzer.BeanDependency(injectedBeanCount=" + this.getInjectedBeanCount()
                    + ", injectedBeanNames=" + this.getInjectedBeanNames() + ", circularDependencyCount="
                    + this.getCircularDependencyCount() + ", circularDependencyDescriptions="
                    + this.getCircularDependencyDescriptions() + ")";
        }
    }

    public static class BeanDependencyStatistic {

        private final int allBeanCircularDependencyCount;
        private final Map<String, BeanDependency> dependencyMap;

        @java.beans.ConstructorProperties({ "allBeanCircularDependencyCount", "dependencyMap" })
        public BeanDependencyStatistic(int allBeanCircularDependencyCount,
                Map<String, BeanDependency> dependencyMap) {
            this.allBeanCircularDependencyCount = allBeanCircularDependencyCount;
            this.dependencyMap = dependencyMap;
        }

        public int getAllBeanCircularDependencyCount() {
            return this.allBeanCircularDependencyCount;
        }

        public Map<String, BeanDependency> getDependencyMap() {
            return this.dependencyMap;
        }
    }
}
