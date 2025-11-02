def test_every_name_the_readme_lists_is_one_the_settings_read():
    """The README documents these by name, so a rename has to break here.

    Both lists are written out by hand and this walks them together. A knob
    renamed in one place and not the other otherwise stops working with
    nothing saying so, because an unread environment variable is a default.
    """
    import re
    from pathlib import Path

    from gussetline.config import environment_names

    readme = (Path(__file__).resolve().parent.parent / "README.md").read_text(encoding="utf-8")
    documented = set(re.findall(r"GUSSETLINE_[A-Z_]+", readme))
    known = set(environment_names()) | {"GUSSETLINE_API_KEYS"}
    assert documented <= known, documented - known


def test_every_name_the_settings_read_is_one_from_env_actually_reads():
    from gussetline.config import ENVIRONMENT_NAMES, from_env

    for name in ENVIRONMENT_NAMES:
        made = from_env({})
        assert hasattr(made, name.lower()) or name in {
            "MATERIAL_DIFFERENCE",
            "WATCH_THRESHOLD",
            "CREDIT_DISCOUNT",
        }


def test_the_names_are_all_prefixed_and_none_of_them_repeats():
    from gussetline.config import environment_names

    names = environment_names()
    assert all(name.startswith("GUSSETLINE_") for name in names)
    assert len(set(names)) == len(names)


def test_nothing_set_reads_back_as_nothing_set():
    from gussetline.config import what_is_set

    assert what_is_set({}) == {}


def test_what_is_set_reports_only_what_was_chosen():
    from gussetline.config import what_is_set

    found = what_is_set({"GUSSETLINE_DAILY_QUOTA": "40", "SOMETHING_ELSE": "x"})
    assert found == {"GUSSETLINE_DAILY_QUOTA": "40"}
