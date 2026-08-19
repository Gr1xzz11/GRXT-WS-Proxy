from __future__ import annotations

from dataclasses import dataclass
import re

_SEMVER = re.compile(r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$")


def _pre_parts(value: str) -> tuple[tuple[int, object], ...]:
    out: list[tuple[int, object]] = []
    for part in value.split("."):
        if part.isdigit():
            out.append((0, int(part)))
        else:
            out.append((1, part))
    return tuple(out)


@dataclass(frozen=True, order=False)
class Version:
    major: int
    minor: int
    patch: int
    prerelease: str | None = None

    @classmethod
    def parse(cls, value: str) -> "Version":
        match = _SEMVER.fullmatch(value.strip())
        if not match:
            raise ValueError(f"Invalid semantic version: {value!r}")
        return cls(int(match[1]), int(match[2]), int(match[3]), match[4])

    def _core(self) -> tuple[int, int, int]:
        return self.major, self.minor, self.patch

    def __lt__(self, other: "Version") -> bool:
        if self._core() != other._core():
            return self._core() < other._core()
        if self.prerelease is None:
            return False
        if other.prerelease is None:
            return True
        a, b = _pre_parts(self.prerelease), _pre_parts(other.prerelease)
        for left, right in zip(a, b):
            if left == right:
                continue
            if left[0] != right[0]:
                return left[0] < right[0]
            return left[1] < right[1]
        return len(a) < len(b)

    def __eq__(self, other: object) -> bool:
        if not isinstance(other, Version):
            return NotImplemented
        return self._core() == other._core() and self.prerelease == other.prerelease


def update_state(current: str, minimum: str, latest: str) -> str:
    cur = Version.parse(current)
    min_v = Version.parse(minimum)
    latest_v = Version.parse(latest)
    if cur < min_v:
        return "required"
    if cur < latest_v:
        return "available"
    return "current"
