# Receptenboek

Deel een recept vanuit Instagram, YouTube of een website. Gemini zet het om naar een Nederlands recept in je eigen receptenboek.

- **iPhone:** open de web-app en volg de [handleiding](docs/HANDLEIDING.md).
- **Android:** eigen app (APK), broncode in `bron/android`.

Iedereen gebruikt een eigen, gratis Gemini API-sleutel. Recepten worden alleen op je eigen telefoon bewaard.

## Mappen
- `docs/` – de iPhone/web-app zoals die via GitHub Pages online staat.
- `bron/web/` – web-specifieke bestanden (`build-web.sh` voegt ze samen met de gedeelde UI uit `bron/android/assets/www`).
- `bron/android/` – Android-app (`build.sh`). De ondertekeningssleutel staat niet in deze openbare repository.
