package com.example.mfaapp.config;

import com.example.mfaapp.domain.ContentType;
import com.example.mfaapp.domain.Difficulty;
import com.example.mfaapp.domain.Enrollment;
import com.example.mfaapp.domain.Lesson;
import com.example.mfaapp.domain.LessonProgress;
import com.example.mfaapp.domain.Module;
import com.example.mfaapp.domain.ModuleCategory;
import com.example.mfaapp.domain.Role;
import com.example.mfaapp.domain.User;
import com.example.mfaapp.repo.EnrollmentRepository;
import com.example.mfaapp.repo.LessonProgressRepository;
import com.example.mfaapp.repo.ModuleRepository;
import com.example.mfaapp.repo.UserRepository;
import com.example.mfaapp.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Seeds a dataset that puts every UI state on screen at first run: modules with and without
 * thumbnails, one completed enrollment, two partially-finished ones, and a "continue learning"
 * candidate.
 *
 * <p>Excluded from the test profile — tests build their own fixtures so their assertions do not
 * depend on demo content.
 */
@Component
@Profile("!test")
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    static final String DEMO_USERNAME = "demo";
    static final String DEMO_PASSWORD = "Demo@12345";

    private final UserRepository users;
    private final UserService userService;
    private final ModuleRepository modules;
    private final EnrollmentRepository enrollments;
    private final LessonProgressRepository lessonProgress;
    private final Clock clock;

    public DataSeeder(UserRepository users, UserService userService, ModuleRepository modules,
                      EnrollmentRepository enrollments, LessonProgressRepository lessonProgress,
                      Clock clock) {
        this.users = users;
        this.userService = userService;
        this.modules = modules;
        this.enrollments = enrollments;
        this.lessonProgress = lessonProgress;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (users.existsByUsername(DEMO_USERNAME)) {
            log.info("Seed data already present; skipping.");
            return;
        }

        // MFA is intentionally not enrolled, so the first login walks the setup flow.
        User demo = userService.createUser(DEMO_USERNAME, DEMO_PASSWORD, EnumSet.of(Role.USER));
        List<Module> seeded = seedModules();

        seedProgress(demo, seeded);
        log.info("Seeded demo user '{}' and {} modules.", DEMO_USERNAME, seeded.size());
    }

    private List<Module> seedModules() {
        Instant now = clock.instant();
        List<Module> saved = new ArrayList<>();
        List<ModuleSpec> specs = moduleSpecs();
        for (int i = 0; i < specs.size(); i++) {
            ModuleSpec spec = specs.get(i);
            // Stagger createdAt so the "Newest" sort has something meaningful to order by.
            Instant createdAt = now.minus(Duration.ofDays(specs.size() - i));
            Module module = new Module(spec.slug(), spec.title(), spec.summary(), spec.description(),
                    spec.category(), spec.difficulty(), spec.totalMinutes(), spec.thumbnailUrl(),
                    true, spec.requiredRole(), i + 1, createdAt);
            for (int l = 0; l < spec.lessons().size(); l++) {
                LessonSpec lesson = spec.lessons().get(l);
                module.addLesson(lesson.title(), l + 1, lesson.contentType(),
                        "content/" + spec.slug() + "/" + (l + 1), lesson.minutes());
            }
            saved.add(modules.save(module));
        }
        return saved;
    }

    /**
     * One completed module, two in progress with partial lesson progress, everything else not
     * enrolled. The most recently viewed in-progress module becomes "continue learning".
     */
    private void seedProgress(User demo, List<Module> seeded) {
        Instant now = clock.instant();
        completeFully(demo, bySlug(seeded, "phishing-resistance-fundamentals"), now.minus(Duration.ofDays(6)));
        completePartially(demo, bySlug(seeded, "reading-the-product-roadmap"), 1, now.minus(Duration.ofDays(3)));
        // Viewed most recently, so this is the module the dashboard offers to resume.
        completePartially(demo, bySlug(seeded, "designing-idempotent-apis"), 2, now.minus(Duration.ofHours(5)));
    }

    private Module bySlug(List<Module> seeded, String slug) {
        return seeded.stream()
                .filter(module -> module.getSlug().equals(slug))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Seed data is missing module " + slug));
    }

    private void completeFully(User user, Module module, Instant at) {
        completePartially(user, module, module.getLessons().size(), at);
    }

    private void completePartially(User user, Module module, int lessonsToComplete, Instant at) {
        Enrollment enrollment = enrollments.save(new Enrollment(user, module, at));
        List<Lesson> ordered = module.getLessons().stream()
                .sorted((a, b) -> Integer.compare(a.getOrderIndex(), b.getOrderIndex()))
                .toList();

        int completed = Math.min(lessonsToComplete, ordered.size());
        for (int i = 0; i < completed; i++) {
            LessonProgress progress = new LessonProgress(enrollment, ordered.get(i), at);
            progress.markCompleted(at.plus(Duration.ofMinutes(i * 10L)));
            lessonProgress.save(progress);
        }
        enrollment.applyProgress(completed, ordered.size(), at);
        enrollment.touchViewed(at);
        enrollments.save(enrollment);
    }

    private record LessonSpec(String title, ContentType contentType, int minutes) {
    }

    private record ModuleSpec(String slug, String title, String summary, String description,
                              ModuleCategory category, Difficulty difficulty, String thumbnailUrl,
                              Role requiredRole, List<LessonSpec> lessons) {

        int totalMinutes() {
            return lessons.stream().mapToInt(LessonSpec::minutes).sum();
        }
    }

    private static LessonSpec video(String title, int minutes) {
        return new LessonSpec(title, ContentType.VIDEO, minutes);
    }

    private static LessonSpec article(String title, int minutes) {
        return new LessonSpec(title, ContentType.ARTICLE, minutes);
    }

    private static LessonSpec quiz(String title, int minutes) {
        return new LessonSpec(title, ContentType.QUIZ, minutes);
    }

    private static List<ModuleSpec> moduleSpecs() {
        return List.of(
                new ModuleSpec("phishing-resistance-fundamentals",
                        "Phishing Resistance Fundamentals",
                        "Spot the handful of signals that give away almost every credential-harvesting attempt.",
                        """
                        Attackers rarely need a zero-day when a convincing email will do. This module walks \
                        through the anatomy of a modern phishing campaign, from domain look-alikes and \
                        display-name spoofing through to consent-screen abuse on OAuth applications. You will \
                        practise triaging real reported messages, learn why hardware-backed and TOTP second \
                        factors change the economics for an attacker, and finish with the reporting workflow \
                        this organisation expects you to follow.""",
                        ModuleCategory.SECURITY, Difficulty.BEGINNER, "/img/thumb-security.svg", null,
                        List.of(video("Why phishing still works", 8),
                                article("Anatomy of a look-alike domain", 10),
                                video("Consent-screen and OAuth abuse", 12),
                                article("Reporting a suspicious message", 6),
                                quiz("Spot the phish", 9))),

                new ModuleSpec("incident-response-playbook",
                        "Incident Response Playbook",
                        "Run the first sixty minutes of an incident without losing evidence or momentum.",
                        """
                        The first hour decides how long an incident lasts. This module covers declaring an \
                        incident, assigning the commander and scribe roles, and the order in which you \
                        contain, preserve and communicate. It includes the evidence-handling rules that keep \
                        a forensic timeline admissible, the customer-communication templates, and a tabletop \
                        exercise that runs an intrusion end to end. Expect to leave able to take the pager \
                        for a severity-one event.""",
                        ModuleCategory.SECURITY, Difficulty.ADVANCED, null, null,
                        List.of(video("Declaring an incident", 7),
                                article("Commander, scribe and comms roles", 9),
                                article("Containment without destroying evidence", 14),
                                video("Building the forensic timeline", 13),
                                article("Customer communication templates", 8),
                                quiz("Tabletop: the exfiltration alert", 20))),

                new ModuleSpec("secrets-management-in-practice",
                        "Secrets Management in Practice",
                        "Move credentials out of config files and into something you can rotate on a Friday.",
                        """
                        Hard-coded secrets are the debt that compounds fastest. This module explains envelope \
                        encryption, the difference between a secret store and a key management service, and \
                        how short-lived dynamic credentials remove whole categories of incident. You will \
                        migrate a service from a checked-in properties file to injected secrets, add \
                        rotation, and set up detection so the next accidental commit is caught before it \
                        reaches a shared branch.""",
                        ModuleCategory.SECURITY, Difficulty.INTERMEDIATE, null, null,
                        List.of(article("Why config files leak", 7),
                                video("Envelope encryption explained", 11),
                                article("Secret stores versus KMS", 9),
                                video("Migrating a live service", 15),
                                article("Rotation without downtime", 10),
                                quiz("Rotate it safely", 8))),

                new ModuleSpec("designing-idempotent-apis",
                        "Designing Idempotent APIs",
                        "Make retries boring: the same request twice should never charge the customer twice.",
                        """
                        Networks retry, clients double-click and queues redeliver. An endpoint that cannot \
                        absorb a duplicate will eventually corrupt data. This module covers idempotency keys, \
                        choosing the right uniqueness constraint, the difference between idempotent and \
                        merely safe methods, and how to make a create-or-return endpoint that never answers \
                        409. It closes with concurrency: what actually happens when two identical requests \
                        land in the same millisecond.""",
                        ModuleCategory.ENGINEERING, Difficulty.INTERMEDIATE, "/img/thumb-engineering.svg", null,
                        List.of(article("Safe, idempotent, neither", 8),
                                video("Idempotency keys end to end", 13),
                                article("Picking the uniqueness constraint", 11),
                                video("Create-or-return without 409", 10),
                                article("Two requests, one millisecond", 12),
                                quiz("Design review: the payments endpoint", 10))),

                new ModuleSpec("database-indexing-deep-dive",
                        "Database Indexing Deep Dive",
                        "Read a query plan, then fix the query instead of adding another index.",
                        """
                        Most slow queries are not missing an index; they are using the wrong one. This module \
                        teaches you to read an execution plan properly, recognise when a composite index's \
                        column order defeats it, and understand selectivity well enough to predict what the \
                        planner will do. It covers covering indexes, partial indexes, the write cost of every \
                        index you add, and the specific shapes — leading wildcards, functions on columns, \
                        implicit casts — that silently disable one.""",
                        ModuleCategory.ENGINEERING, Difficulty.ADVANCED, null, null,
                        List.of(video("Reading an execution plan", 14),
                                article("Composite index column order", 12),
                                article("Selectivity and cardinality", 10),
                                video("Covering and partial indexes", 13),
                                article("How to disable your own index", 9),
                                article("The write cost of an index", 8),
                                quiz("Diagnose six slow queries", 18))),

                new ModuleSpec("observability-traces-metrics",
                        "Observability with Traces and Metrics",
                        "Instrument a service so the next outage is a five-minute question, not an archaeology dig.",
                        """
                        Logs tell you what one process did; traces tell you what the request did. This module \
                        covers the three signals and when each earns its keep, how to propagate trace context \
                        across a queue boundary, and why high-cardinality attributes are the ones worth \
                        paying for. You will instrument a service by hand, build a dashboard that answers a \
                        specific question rather than showing everything, and write alerts on symptoms \
                        instead of causes.""",
                        ModuleCategory.ENGINEERING, Difficulty.INTERMEDIATE, null, null,
                        List.of(article("Logs, metrics, traces", 8),
                                video("Propagating context across a queue", 12),
                                article("High-cardinality attributes", 9),
                                video("Instrumenting a service by hand", 14),
                                article("Alert on symptoms, not causes", 10),
                                quiz("Find the latency source", 11))),

                new ModuleSpec("soc-2-readiness-essentials",
                        "SOC 2 Readiness Essentials",
                        "What an auditor actually asks for, and how to have it ready before they ask.",
                        """
                        A SOC 2 report is mostly evidence collection, and evidence collected after the fact \
                        is expensive. This module maps the trust services criteria onto controls you can \
                        automate, explains the difference between Type I and Type II and why the observation \
                        window matters, and shows what an auditor's sample request looks like in practice. \
                        You will finish knowing which artefacts to capture continuously and which reviews \
                        need a human signature.""",
                        ModuleCategory.COMPLIANCE, Difficulty.BEGINNER, "/img/thumb-compliance.svg", null,
                        List.of(article("Trust services criteria, plainly", 9),
                                video("Type I versus Type II", 7),
                                article("Controls you can automate", 11),
                                article("What a sample request looks like", 8),
                                quiz("Is this evidence sufficient?", 7))),

                new ModuleSpec("data-retention-and-deletion",
                        "Data Retention and Deletion",
                        "Keep what you must, delete what you should, and be able to prove both.",
                        """
                        Retention is a two-sided obligation: some records must survive for years, and others \
                        must not survive at all. This module covers building a retention schedule from legal \
                        and contractual requirements, implementing deletion that reaches backups and \
                        analytics copies, and the distinction between soft delete, anonymisation and genuine \
                        erasure. It ends with handling a subject deletion request against a system that was \
                        never designed for one.""",
                        ModuleCategory.COMPLIANCE, Difficulty.INTERMEDIATE, null, null,
                        List.of(article("Building a retention schedule", 10),
                                video("Deletion that reaches backups", 13),
                                article("Soft delete, anonymise, erase", 9),
                                video("Handling a deletion request", 12),
                                quiz("Retention scenarios", 8))),

                new ModuleSpec("your-first-week",
                        "Your First Week",
                        "Accounts, access and the handful of conventions that make week two easier.",
                        """
                        A short, practical orientation. This module gets your accounts and second factor set \
                        up, explains which channels are for what and the response expectations attached to \
                        each, and introduces the team rituals you will be part of. It finishes with the \
                        conventions that are obvious to everyone who already works here and invisible to \
                        everyone who does not — how work is tracked, how decisions get recorded, and who to \
                        ask when something is unclear.""",
                        ModuleCategory.ONBOARDING, Difficulty.BEGINNER, null, Role.USER,
                        List.of(video("Welcome and orientation", 6),
                                article("Accounts and your second factor", 8),
                                article("Which channel for what", 7),
                                video("Team rituals", 9),
                                quiz("Find the right owner", 5))),

                new ModuleSpec("how-we-ship-software",
                        "How We Ship Software",
                        "From branch to production, including what to do when it goes wrong.",
                        """
                        Our delivery path end to end: branching and review expectations, what the pipeline \
                        checks and why each gate exists, how feature flags let a deploy and a release happen \
                        on different days, and the rollback procedure. This module also covers the on-call \
                        handover and what "done" means here, so your first change reaches production without \
                        anyone having to explain the process to you twice.""",
                        ModuleCategory.ONBOARDING, Difficulty.BEGINNER, null, null,
                        List.of(article("Branching and review", 7),
                                video("What the pipeline checks", 10),
                                article("Feature flags and dark launches", 9),
                                video("Rolling back safely", 8),
                                article("On-call handover", 7),
                                quiz("Ship a change", 6))),

                new ModuleSpec("reading-the-product-roadmap",
                        "Reading the Product Roadmap",
                        "Understand what a roadmap commits to, what it does not, and how to read the gaps.",
                        """
                        Roadmaps are communication instruments, not schedules. This module explains the \
                        difference between a now/next/later horizon and a dated plan, how bets are sized and \
                        why some deliberately fail, and how to trace a roadmap item back to the customer \
                        problem that justified it. You will learn to read what a roadmap is quietly \
                        deprioritising, which is usually the more useful signal.""",
                        ModuleCategory.PRODUCT, Difficulty.BEGINNER, "/img/thumb-product.svg", null,
                        List.of(video("Now, next, later", 7),
                                article("Sizing a bet", 9),
                                article("From customer problem to roadmap item", 10),
                                video("Reading the deprioritised list", 8),
                                quiz("Interpret this roadmap", 6))),

                new ModuleSpec("pricing-and-packaging-basics",
                        "Pricing and Packaging Basics",
                        "Why the tier boundaries sit where they do, and what moves when you change one.",
                        """
                        Packaging decides who can buy and what they expect. This module covers value metrics \
                        and how to pick one that grows with the customer, where to draw tier boundaries so \
                        upgrades feel earned rather than extracted, and the mechanics of grandfathering an \
                        existing base through a change. It closes on discounting discipline and the \
                        second-order effects a headline price change has on support load and churn.""",
                        ModuleCategory.PRODUCT, Difficulty.INTERMEDIATE, null, null,
                        List.of(article("Choosing a value metric", 10),
                                video("Where tier boundaries belong", 12),
                                article("Grandfathering an existing base", 9),
                                video("Discounting discipline", 8),
                                article("Second-order effects of a price change", 11),
                                quiz("Repackage this product", 9))));
    }
}
