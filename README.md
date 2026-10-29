# Audioteka – odtwarzacz audiobooków (e-ink)

Samodzielna aplikacja na Androida: bez Chrome, bez Termuksa w tle, działa offline.

## Funkcje
- wybór folderów z audiobookami (systemowe okno Androida), skanowanie tylko w nich
- podfolder = książka; części CD1/CD2… łączone w jedną; luźne pliki grupowane po nazwie
- pamięta miejsce w każdej książce, „Słuchaj dalej”, zakładki W trakcie / Nowe / Ukończone, wyszukiwarka
- odtwarzanie w tle, powiadomienie i sterowanie z ekranu blokady / słuchawek
- prędkość 0,5–3× (co 0,05), przeskoki 10–60 s, cofanie po pauzie
- wyłącznik czasowy (5–60 min lub koniec rozdziału), potrząśnięcie = od nowa, wyciszanie na końcu
- zakładki z notatkami, lista rozdziałów, okładki (cover.jpg lub wbudowane) w odcieniach szarości
- statystyki słuchania, ukrywanie pozycji, tryb białe na czarnym
- rozdziały wbudowane w pliki (M4B/M4A: chpl i ścieżka rozdziałów, MP3: ID3 CHAP)
- pomijanie ciszy i wzmocnienie głośności (+3/+6/+10 dB)
- powiadomienie i ekran blokady: rozdział wstecz/naprzód, przewijanie, pauza
- widżet na ekran główny (okładka, rozdział, 5 przycisków)
- scalanie części w jeden plik MP3 lub M4B z rozdziałami
- okładki: z folderu, z pliku, z galerii, z internetu (Google Books, Open Library) albo generowane

## Budowanie (tak samo jak E-ink Launcher)
1. Na github.com utwórz nowe, puste, prywatne repozytorium `audioteka`.
2. W Termuksie (zamień `TWOJ_LOGIN`; hasło = token GitHub z uprawnieniami repo + workflow):
```
cd ~ && unzip -o ~/storage/downloads/Audioteka.zip && cd Audioteka
git init && git add . && git -c user.name=ja -c user.email=ja@x.pl commit -m start
git branch -M main
git remote add origin https://github.com/TWOJ_LOGIN/audioteka.git
git push -u origin main
```
3. github.com → repozytorium → Actions → Build APK → po kilku minutach Artifacts → `Audioteka-apk`.
4. Rozpakuj i zainstaluj APK (albo przez Shizuku / ADB jak launcher).

## Aktualizacje
**W aplikacji:** Ustawienia → Aktualizacje → wklej token GitHub. Audioteka sama sprawdza przy starcie,
czy jest nowa wersja, pokazuje „Nowa wersja” i instaluje ją jednym stuknięciem (GitHub publikuje każdy
build jako Release `build-N`, numer wersji rośnie automatycznie).

**Wysłanie nowej wersji kodu (Termux)** – pobierz nowy Audioteka.zip i wklej:
```
cd ~ && unzip -o "$(ls -t ~/storage/downloads/Audioteka*.zip | head -1)" && cd Audioteka && git add -A && git -c user.name=ja -c user.email=ja@x.pl commit -m aktualizacja && git push
```
Podpis: `app/audioteka.keystore` (hasło `audioteka`) – aktualizacje instalują się na starą wersję.
