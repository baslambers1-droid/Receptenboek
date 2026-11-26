# Receptenboek op de iPhone

Deel een recept vanuit Instagram, YouTube of een website. Gemini leest de caption en de video en zet het recept in het Nederlands in je eigen receptenboek. Alles blijft op je eigen iPhone.

---

## Deel 1 – Voor iedereen (5 minuten)

1. **Open de app-link** in Safari: `https://baslambers1-droid.github.io/Receptenboek/`
2. Tik op het **deel-icoon** (vierkant met pijl) → **Zet op beginscherm** → zet **Open als webapp** aan → **Voeg toe**.
3. Maak een **gratis Gemini-sleutel** aan op <https://aistudio.google.com/apikey>: log in met je Google-account en tik op **Create API key**. Kopieer de sleutel.
4. Open **Receptenboek** vanaf je beginscherm en plak de sleutel.
5. Installeer de **Opdracht** via de link die je van je vriend hebt gekregen. Bij het installeren wordt gevraagd om je Gemini-sleutel: plak dezelfde sleutel.

### Zo voeg je een recept toe
1. In Instagram: tik bij een post of reel op **Delen** (papieren vliegtuigje) → scroll in de onderste rij naar rechts → **Meer** → **Receptenboek**. *(Je kunt "Receptenboek" naar voren zetten via Wijzig acties.)*
2. Wacht tot de melding **"Klaar!"** komt (meestal 5 tot 20 seconden).
3. Open de Receptenboek-app en tik op **Recept plakken uit de Opdracht** → **Plak**.

YouTube en websites werken hetzelfde. Lukt een Instagram-post niet? Maak een **schermopname** van de reel (met geluid) en kies in de app **+ → Kies screenshots of een video**.

---

## Deel 2 – De Opdracht maken (eenmalig, ±20 minuten)

Dit hoeft maar **één persoon** te doen. Daarna deel je de Opdracht met een iCloud-link en kan iedereen hem met één tik installeren.

Open de app **Opdrachten** → **+** (nieuwe opdracht) → noem hem **Receptenboek**.
Tik bovenaan op de naam → **ⓘ Details** → zet **Toon in deelmenu** aan.
Bovenaan staat nu *"Ontvang … van Deelmenu"*. Tik op het eerste blauwe woord en laat alleen **URL's** en **Tekst** aangevinkt.
Tik op *"Indien er geen invoer is"* → **Stop en antwoord** (mag ook zo blijven).

Voeg nu deze acties toe, in deze volgorde. Zoek ze via de zoekbalk onderaan. *(Engelse naam tussen haakjes, voor als je iPhone in het Engels staat.)*

### A. Basis

1. **Tekst** *(Text)* – typ je Gemini-sleutel. Tik lang op de actie → **Wijzig naam** is niet nodig; voeg direct daarna toe:
2. **Stel variabele in** *(Set Variable)* – naam: `Sleutel`, invoer: *Tekst*.
3. **Haal URL's op uit invoer** *(Get URLs from Input)* – invoer: *Opdrachtinvoer*.
4. **Haal eerste onderdeel uit lijst** *(Get Item from List → First Item)*.
5. **Stel variabele in** – naam: `Link`.
6. **Woordenlijst** *(Dictionary)* – voeg twee **Tekst**-velden toe:
   - `app` = `receptenboek`
   - `url` = variabele **Link**
7. **Stel variabele in** – naam: `Pakket`.

### B. Instagram

8. **Als** *(If)* – **Link** · **bevat** · `instagram.com`
9. **Zoek naar overeenkomst in tekst** *(Match Text)* – patroon: `/(?:p|reel|reels|tv)/([A-Za-z0-9_-]+)` · in: **Link**.
10. **Haal groep op uit overeenkomst** *(Get Group from Matched Text)* – **Groep op index 1**.
11. **Stel variabele in** – naam: `Code`.
12. **Haal inhoud van URL op** *(Get Contents of URL)* – URL: `https://www.instagram.com/p/`**Code**`/embed/captioned/`
    Tik op *Toon meer* → **Headers** → voeg toe: `User-Agent` = `Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1`
13. **Stel variabele in** – naam: `Html`, invoer: *Inhoud van URL*. *(Gebruik hier geen "Haal tekst op uit invoer": dat haalt de HTML-code weg die we nodig hebben.)*
14. *(geen actie: ga door naar 15)*
15. **Stel woordenlijstwaarde in** *(Set Dictionary Value)* – sleutel `html`, waarde **Html**, in woordenlijst **Pakket**. Daarna **Stel variabele in** → `Pakket` (met het resultaat).
16. **Vervang tekst** *(Replace Text)* – zoek `\\+/` · vervang door `/` · in **Html** · tik *Toon meer* → **Reguliere expressie** aan.
17. **Vervang tekst** – zoek `\\+u0026` · vervang door `&` · in *Vervangen tekst* · reguliere expressie aan.
18. **Vervang tekst** – zoek `\\+"` · vervang door `"` · in *Vervangen tekst* · reguliere expressie aan.
19. **Stel variabele in** – naam: `Schoon`.

**Video naar Gemini:**

20. **Zoek naar overeenkomst in tekst** – patroon: `"video_url":"([^"]+)"` · in: **Schoon**.
21. **Haal groep op uit overeenkomst** – **Groep op index 1**.
22. **Als** – *Groep* · **heeft een waarde** *(has any value)*
23. **Haal inhoud van URL op** – URL: *Groep* (de video wordt gedownload).
24. **Haal inhoud van URL op** – URL: `https://generativelanguage.googleapis.com/upload/v1beta/files?uploadType=media`
    - Methode: **POST**
    - Headers: `x-goog-api-key` = **Sleutel**, `Content-Type` = `video/mp4`
    - Verzoekhoofdtekst *(Request Body)*: **Bestand** → *Inhoud van URL* (van stap 23)
25. **Haal woordenlijstwaarde op** *(Get Dictionary Value)* – sleutel: `file.uri` *(lukt dat niet: eerst sleutel `file`, daarna nog een keer met sleutel `uri`)*
26. **Stel woordenlijstwaarde in** – sleutel `fileUri`, waarde: *Woordenlijstwaarde*, in **Pakket** → **Stel variabele in** `Pakket`.
27. **Stel woordenlijstwaarde in** – sleutel `fileMime`, waarde `video/mp4`, in **Pakket** → **Stel variabele in** `Pakket`.
28. **Einde als** *(End If)* (hoort bij stap 22).

**Omslagfoto:**

29. **Zoek naar overeenkomst in tekst** – patroon: `"display_url":"([^"]+)"` · in **Schoon**.
30. **Haal groep op uit overeenkomst** – Groep op index 1.
31. **Als** – *Groep* · heeft een waarde
32. **Haal inhoud van URL op** – URL: *Groep*.
33. **Wijzig grootte afbeelding** *(Resize Image)* – breedte `900`, hoogte automatisch.
34. **Converteer afbeelding** *(Convert Image)* – naar **JPEG**.
35. **Codeer met base64** *(Base64 Encode)* – regeleinden: **Geen** *(None)*.
36. **Stel woordenlijstwaarde in** – sleutel `thumb`, waarde *Base64-gecodeerd*, in **Pakket** → **Stel variabele in** `Pakket`.
37. **Einde als** (hoort bij stap 31).

### C. Websites (alles behalve Instagram en YouTube)

38. **Anders** *(Otherwise)* – hoort bij de **Als** van stap 8.
39. **Als** – **Link** · **bevat niet** · `youtu`
40. **Haal inhoud van URL op** – URL: **Link** (zelfde User-Agent-header als stap 12).
41. *(geen actie)*
42. **Stel woordenlijstwaarde in** – sleutel `html`, waarde *Inhoud van URL*, in **Pakket** → **Stel variabele in** `Pakket`.
43. **Einde als** (stap 39).
44. **Einde als** (stap 8).

### D. Afronden

45. **Haal tekst op uit invoer** – invoer: **Pakket** (maakt er JSON-tekst van).
46. **Kopieer naar klembord** *(Copy to Clipboard)*.
47. **Toon melding** *(Show Notification)* – `Klaar! Open Receptenboek en tik op Recept plakken.`

### Testen
Deel een Instagram-reel naar **Receptenboek**, open de app en tik op **Recept plakken**. Werkt het? Dan delen:

### Delen met vrienden zonder je eigen sleutel mee te sturen
1. Open de Opdracht → tik op de naam → **ⓘ Details** → **Importvragen** *(Import Questions)* → **Voeg nieuwe vraag toe** → kies de **Tekst** van stap 1. Vraag: `Plak je eigen Gemini API-sleutel`.
2. Tik op het **deel-icoon** → **Kopieer iCloud-link**.
3. Stuur die link naar je vrienden. Bij het installeren vult iedereen zijn eigen sleutel in. **Jouw sleutel wordt dan niet meegestuurd.**

---

### Problemen?
- **"Geen recept op het klembord"**: de Opdracht is niet tot het einde gekomen. Open de app Opdrachten en draai hem nog eens met een link om te zien waar hij stopt.
- **Instagram-post zonder video**: niet elke post geeft de video vrij. Het recept wordt dan uit de caption en de foto gehaald. Staat het recept alleen in de video? Gebruik dan een schermopname via **+** in de app.
- **"Daglimiet bereikt"**: de gratis Gemini-limiet is per dag. Probeer het morgen opnieuw.
