"""Entry point so `python -m autoflow` and the PyInstaller bundle both work."""
from .cli import main

if __name__ == "__main__":
    raise SystemExit(main())
