import hashlib
import os

def file_size(path: str) -> int:
    """Return the size of the file at *path* in bytes."""
    return os.path.getsize(path)

def hash_file(path: str, algorithm: str) -> str:
    """Compute the hex digest of *path* using the given hash *algorithm*."""
    with open(path, "rb") as f:
        dig = hashlib.new(algorithm)
        while chunk := f.read(64 * 1024):
            dig.update(chunk)
    return dig.hexdigest()

def hash_file_many(path: str, algorithms: list[str]) -> dict[str, str]:
    """
    Return a dict mapping each algorithm name to its hex digest for *path*.
    All algorithms are computed in a single pass.
    """
    digests = {algo: hashlib.new(algo) for algo in algorithms}
    with open(path, "rb") as f:
        while chunk := f.read(64 * 1024):
            for d in digests.values():
                d.update(chunk)
    return {algo: d.hexdigest() for algo, d in digests.items()}
