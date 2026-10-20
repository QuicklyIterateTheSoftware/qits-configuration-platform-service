-- RELEASE ADOPTION (qits-1472): a released version moves a pin only once its release is FINALIZED.
--
-- Until this migration the bus listener wrote a pin the moment qits-ci announced a SoftwareRelease.
-- That announcement comes after RELEASED and BEFORE the post-release gates qits-projects finalizes on
-- (the publish run green, the deployment active), and a RELEASED request may never finalize at all —
-- it goes OBSOLETE when a newer request folds its tag in. So a pin could name a version that never
-- passed its deploy gate, or one whose request was abandoned. FINALIZATION is the platform's green
-- light, and these two tables are how a pin waits for it.
--
-- THE JOIN KEY IS (repository, version). SoftwareRelease's `repository` and ReleaseRequestStateChanged's
-- `repoId` are the same qits-projects repository row id; neither event names the other's facts, so
-- the two halves are written down as they arrive and adoption happens on whichever arrives second.
--
-- NO FOREIGN KEYS and no expiry, the same stance V1 and V3 take: a published row with no finalized
-- row at or above its version is a release that was never cleared, and it stays as the record that it
-- was announced. Rows are a handful a day.

create table configuration_release_finalized (
    id uuid not null,

    -- qits-projects' repository row id, as ReleaseRequestStateChanged.repoId carries it. Opaque here.
    repository varchar(255) not null,

    -- The version the finalized request released. Compared with control/VersionOrder, never in SQL:
    -- a published artifact of this repository at or BELOW it is cleared, not only an equal one —
    -- an OBSOLETE request's tag is folded into the next one, and an `if-changed` artifact is not
    -- announced again at a version where it did not change.
    version varchar(128) not null,

    finalized_at timestamp(6) with time zone not null,

    primary key (id),

    -- FINALIZED is often announced TWICE, with distinct event ids milliseconds apart, so dedup by
    -- event id is not enough. This constraint is what the listener's `on conflict do nothing` rests on.
    constraint uq_configuration_release_finalized unique (repository, version)
);

create table configuration_release_published (
    id uuid not null,

    -- SoftwareRelease.repository: the same row id as configuration_release_finalized.repository.
    repository varchar(255) not null,
    version varchar(128) not null,

    -- The package as qits-ci announced it, compared as the literal wire strings the pin match uses.
    package_type varchar(32) not null,
    package_name varchar(255) not null,

    recorded_at timestamp(6) with time zone not null,

    -- NULL until this release has been adopted: its pins written, or deliberately not written because
    -- a higher version of the same package was adopted first (a pin never moves backwards). Set
    -- AFTER the pin writes commit, so a crash in between re-adopts rather than strands.
    adopted_at timestamp(6) with time zone,

    primary key (id),

    -- A redelivered SoftwareRelease is the same row, which is what keeps the record idempotent.
    constraint uq_configuration_release_published
        unique (repository, version, package_type, package_name)
);

-- "Every unadopted release of this repository" is the FINALIZED side's question; the unique
-- constraint above leads with `repository` and answers it, so no further index is needed for it.
-- "The highest adopted version of this package" is the no-rewind check's question, and this index is
-- that one.
create index idx_configuration_release_published_package
    on configuration_release_published (package_type, package_name);
