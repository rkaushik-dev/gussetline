from __future__ import annotations

import gzip
import os
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from unittest import mock

import pytest

from gussetline.collect import archive as archive_module
from gussetline.collect.archive import PARTIAL, Archive, Sighting, digest_of
from gussetline.errors import ValidationError

SEEN = datetime(2025, 5, 12, 9, 0)
LATER = datetime(2025, 5, 12, 15, 30)
PAYLOAD = b'{"fares":[{"basis":"KLWAP7RT"}]}'
URL = "/search"


@pytest.fixture
def archive(tmp_path):
    return Archive(tmp_path / "raw")


def test_a_digest_is_stable_and_depends_on_the_bytes():
    assert digest_of(b"one") == digest_of(b"one")
    assert digest_of(b"one") != digest_of(b"two")
    assert len(digest_of(b"one")) == 32


def test_storing_a_payload_writes_it_and_marks_it_fresh(archive):
    sighting = archive.put(b'{"fares":[]}', "gds", "/search", SEEN)
    assert sighting.fresh
    assert sighting.size == 12
    assert archive.holds(sighting.digest)
    assert archive.get(sighting.digest) == b'{"fares":[]}'


def test_the_same_payload_again_is_not_written_twice(archive):
    first = archive.put(b"same", "gds", "/search", SEEN)
    second = archive.put(b"same", "gds", "/search", LATER)
    assert first.digest == second.digest
    assert first.fresh
    assert not second.fresh
    assert archive.stored_count() == 1


def test_both_sightings_are_recorded_even_when_the_payload_repeats(archive):
    archive.put(b"same", "gds", "/search", SEEN)
    archive.put(b"same", "gds", "/search", LATER)
    rows = archive.manifest()
    assert len(rows) == 2
    assert [r["fresh"] for r in rows] == [True, False]


def test_a_different_payload_gets_its_own_blob(archive):
    archive.put(b"one", "gds", "/search", SEEN)
    archive.put(b"two", "gds", "/search", LATER)
    assert archive.stored_count() == 2


def test_the_payload_is_stored_compressed(tmp_path):
    archive = Archive(tmp_path / "raw")
    payload = b"x" * 5000
    sighting = archive.put(payload, "gds", "/search", SEEN)
    on_disk = archive._path_for(sighting.digest).read_bytes()
    assert len(on_disk) < len(payload)
    assert gzip.decompress(on_disk) == payload


def test_reading_a_digest_we_do_not_hold_is_refused(archive):
    with pytest.raises(ValidationError):
        archive.get("0" * 32)


def test_a_digest_that_is_not_one_is_refused(archive):
    with pytest.raises(ValidationError):
        archive.holds("ab")


def test_the_manifest_of_a_fresh_archive_is_empty(archive):
    assert archive.manifest() == []


def test_the_context_rides_along_on_the_sighting(archive):
    archive.put(
        b"body", "gds", "/search", SEEN, context={"market": "FRA-JFK", "departure": "2025-06-01"}
    )
    assert archive.manifest()[0]["context"]["market"] == "FRA-JFK"


def test_a_non_two_hundred_response_is_archived_too(archive):
    sighting = archive.put(b"gateway timeout", "gds", "/search", SEEN, status=504)
    assert sighting.status == 504
    assert archive.manifest()[0]["status"] == 504


def test_the_sightings_of_one_payload_can_be_listed(archive):
    archive.put(b"same", "gds", "/search", SEEN)
    archive.put(b"other", "gds", "/search", SEEN)
    archive.put(b"same", "gds", "/search", LATER)
    digest = digest_of(b"same")
    assert len(archive.sightings_of(digest)) == 2


def test_replay_gives_every_distinct_payload_once(archive):
    archive.put(b"one", "gds", "/a", SEEN)
    archive.put(b"one", "gds", "/a", LATER)
    archive.put(b"two", "ndc", "/b", SEEN)
    seen = [payload for _, payload in archive.replay()]
    assert sorted(seen) == [b"one", b"two"]


def test_replay_can_be_narrowed_to_one_supplier(archive):
    archive.put(b"one", "gds", "/a", SEEN)
    archive.put(b"two", "ndc", "/b", SEEN)
    assert [p for _, p in archive.replay("ndc")] == [b"two"]


def test_replay_hands_back_the_manifest_row_with_the_payload(archive):
    archive.put(b"one", "gds", "/a", SEEN, context={"market": "FRA-JFK"})
    row, payload = next(iter(archive.replay()))
    assert row["context"]["market"] == "FRA-JFK"
    assert payload == b"one"


def test_an_archive_reopened_on_the_same_directory_keeps_everything(tmp_path):
    first = Archive(tmp_path / "raw")
    sighting = first.put(b"kept", "gds", "/a", SEEN)
    second = Archive(tmp_path / "raw")
    assert second.holds(sighting.digest)
    assert second.get(sighting.digest) == b"kept"
    assert len(second.manifest()) == 1


def test_a_sighting_reads_back_in_one_line():
    sighting = Sighting(digest="abcdef0123", supplier="gds", url="/a", seen_at=SEEN)
    assert str(sighting) == "2025-05-12 09:00 gds new abcdef01"
    stale = Sighting(digest="abcdef0123", supplier="gds", url="/a", seen_at=SEEN, fresh=False)
    assert "same" in str(stale)


def test_an_archive_reads_back_with_its_size(archive):
    archive.put(b"one", "gds", "/a", SEEN)
    assert str(archive).startswith("1 payloads under ")


def test_a_stored_payload_is_checked_against_the_name_it_is_under(archive):
    # The whole reason anybody reads this store is to settle an argument
    # about what a supplier sent. Handing back bytes they did not send is
    # worse than handing back nothing.
    sighting = archive.put(PAYLOAD, "gds", URL, SEEN)
    archive._path_for(sighting.digest).write_bytes(gzip.compress(b"something else entirely"))

    with pytest.raises(ValidationError, match="not what it is filed as"):
        archive.get(sighting.digest)


def test_a_payload_that_will_not_decompress_says_so_rather_than_raising_gzip(archive):
    sighting = archive.put(PAYLOAD, "gds", URL, SEEN)
    archive._path_for(sighting.digest).write_bytes(b"truncated, and not gzip")

    with pytest.raises(ValidationError, match="will not decompress"):
        archive.get(sighting.digest)


def test_a_blob_is_moved_into_place_rather_than_written_in_place(archive):
    moves = []
    real = os.replace

    def watching(source, target):
        moves.append((str(source), str(target)))
        return real(source, target)

    with mock.patch.object(archive_module.os, "replace", watching):
        sighting = archive.put(PAYLOAD, "gds", URL, SEEN)

    assert len(moves) == 1
    source, target = moves[0]
    assert source.endswith(PARTIAL)
    assert target == str(archive._path_for(sighting.digest))


def test_a_write_that_dies_before_the_move_leaves_nothing_under_the_digest(archive):
    # The failure this is all for. A short file under the name of the full
    # payload answers yes to holds, so the payload is never fetched again
    # and nothing ever reports it.
    def dies(source, target):
        raise OSError("the machine went down")

    with mock.patch.object(archive_module.os, "replace", dies), pytest.raises(OSError):
        archive.put(PAYLOAD, "gds", URL, SEEN)

    assert not archive.holds(digest_of(PAYLOAD))


def test_nothing_is_left_behind_by_a_write_that_finished(archive):
    archive.put(PAYLOAD, "gds", URL, SEEN)
    assert list(archive.blobs.rglob("*" + PARTIAL)) == []


def test_a_store_that_is_intact_verifies_clean(archive):
    archive.put(PAYLOAD, "gds", URL, SEEN)
    archive.put(b"another payload", "ndc", URL, SEEN)
    assert archive.verify() == []


def test_verify_names_the_payloads_that_do_not_read_back(archive):
    good = archive.put(PAYLOAD, "gds", URL, SEEN)
    bad = archive.put(b"another payload", "ndc", URL, SEEN)
    archive._path_for(bad.digest).write_bytes(b"not gzip at all")

    assert archive.verify() == [bad.digest]
    assert good.digest not in archive.verify()


def test_verify_leaves_an_unfinished_write_alone(archive):
    # A part file is not a payload that failed its check. It is one that was
    # never finished, and it is not filed under a digest at all.
    sighting = archive.put(PAYLOAD, "gds", URL, SEEN)
    blob = archive._path_for(sighting.digest)
    blob.with_name(blob.name + PARTIAL).write_bytes(b"half a payload")
    assert archive.verify() == []


def test_scrubbing_takes_out_what_cannot_be_read(archive):
    bad = archive.put(b"another payload", "ndc", URL, SEEN)
    archive._path_for(bad.digest).write_bytes(b"not gzip at all")

    corrupt, leftovers = archive.scrub()
    assert (corrupt, leftovers) == (1, 0)
    assert not archive.holds(bad.digest)


def test_scrubbing_a_corrupt_payload_lets_the_next_poll_fetch_it_again(archive):
    # Deleting it is the repair. The digest goes back to being one the desk
    # does not hold, so the collector stops believing it has the payload.
    sighting = archive.put(PAYLOAD, "gds", URL, SEEN)
    archive._path_for(sighting.digest).write_bytes(b"not gzip at all")
    archive.scrub()

    again = archive.put(PAYLOAD, "gds", URL, SEEN)
    assert again.fresh
    assert archive.get(again.digest) == PAYLOAD


def test_scrubbing_takes_out_a_write_that_was_interrupted(archive):
    sighting = archive.put(PAYLOAD, "gds", URL, SEEN)
    blob = archive._path_for(sighting.digest)
    blob.with_name(blob.name + PARTIAL).write_bytes(b"half a payload")

    corrupt, leftovers = archive.scrub()
    assert (corrupt, leftovers) == (0, 1)
    assert archive.get(sighting.digest) == PAYLOAD


def test_scrubbing_a_healthy_store_does_nothing_at_all(archive):
    archive.put(PAYLOAD, "gds", URL, SEEN)
    assert archive.scrub() == (0, 0)
    assert archive.stored_count() == 1


def test_an_unfinished_write_is_not_counted_as_a_stored_payload(archive):
    sighting = archive.put(PAYLOAD, "gds", URL, SEEN)
    blob = archive._path_for(sighting.digest)
    blob.with_name(blob.name + PARTIAL).write_bytes(b"half a payload")
    assert archive.stored_count() == 1


def test_replaying_over_a_corrupt_payload_refuses_rather_than_reparsing_it(archive):
    # A parser fix is rerun over the whole store, and a blob that decompresses
    # to something else would be reparsed into observations nobody can trace.
    sighting = archive.put(PAYLOAD, "gds", URL, SEEN)
    archive._path_for(sighting.digest).write_bytes(gzip.compress(b"something else"))

    with pytest.raises(ValidationError):
        list(archive.replay())


def test_two_threads_storing_the_same_payload_do_not_fight_over_it(archive):
    # The pool stores payloads from several threads at once, and two markets
    # that came back identical are two writers heading for one path.
    seen = []
    with ThreadPoolExecutor(max_workers=8) as pool:
        futures = [pool.submit(archive.put, PAYLOAD, "gds", URL, SEEN) for _ in range(8)]
        for future in futures:
            seen.append(future.result())

    assert len({s.digest for s in seen}) == 1
    assert archive.get(seen[0].digest) == PAYLOAD
    assert archive.stored_count() == 1
    assert list(archive.blobs.rglob("*" + PARTIAL)) == []


def test_a_second_writer_leaves_the_payload_that_is_already_there(archive):
    # Content addressing is what makes the race harmless. Whoever got there
    # first wrote the same bytes, so there is nothing to win.
    first = archive.put(PAYLOAD, "gds", URL, SEEN)
    written = archive._path_for(first.digest).read_bytes()
    archive.put(PAYLOAD, "ndc", URL, LATER)
    assert archive._path_for(first.digest).read_bytes() == written
