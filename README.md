# Milo — robot AI na Androida

Milo to prywatnie instalowana aplikacja Android z polskim czatem AI, własną animowaną twarzą robota, osobną pamięcią i kamerą uruchamianą przez użytkownika.

## Własna postać Milo

Milo ma własną postać 3D: biały robot z niebieskim wizjerem, antenkami i świecącym emblematem. Na ekranie głównym delikatnie kołysze się nad miękką poświatą, mruga, zmienia oczy i usta, gdy słucha, mówi, myśli lub okazuje zaciekawienie. Ikona aplikacji pokazuje jego twarz. Małe funkcje są przypięte do dolnego paska, bez nagłówka z nazwą robota.

## Rozmowa głosowa

Milo domyślnie czyta odpowiedzi najlepszym dostępnym polskim głosem Androida. Ustawienia głosu można otworzyć z ⚙️. Przycisk głośnika wycisza lub włącza odczytywanie. Przycisk mikrofonu uruchamia rozmowę na żywo: Milo nasłuchuje, odpowiada głosem i ponownie nasłuchuje, aż zatrzymasz tryb przyciskiem stop. Mikrofon działa także w trybie kamery. Androidowa usługa rozpoznawania mowy może przetwarzać wypowiedź; do ChatGPT Milo wysyła rozpoznany tekst. Wymagana jest zgoda na mikrofon oraz polski głos zainstalowany na urządzeniu.

## AI przez plan ChatGPT

Milo loguje się do ChatGPT przez oficjalny przepływ **Sign in with ChatGPT**. Po zalogowaniu używa autoryzowanych żądań Responses API bez klucza API. Wymaga to otwartego kodu aplikacji i zgody użytkownika w oknie OpenAI. Aplikacja nie czyta Twoich rozmów w ChatGPT.

Plan ChatGPT Plus może być używany przez kwalifikowane aplikacje open source. Limity użycia są wspólne z innymi aplikacjami korzystającymi z planu; nie tworzą osobnego limitu dla Milo. Dostęp i funkcje zależą od konta oraz bieżących limitów. Szczegóły: [Sign in with ChatGPT](https://developers.openai.com/siwc/quickstart) i [ograniczenia podglądu](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations).

Milo trzyma pamięć i historię rozmów lokalnie na telefonie. Pamięć przekazuje jako kontekst do kolejnych odpowiedzi. Napisz „Zapamiętaj, że…” albo użyj ikony mózgu, by ją wyświetlić i wyczyścić. Tokeny OAuth są szyfrowane lokalnie za pomocą Android Keystore.

## Kamera

Podgląd działa tylko po naciśnięciu ikony aparatu. Podczas podglądu Milo zapisuje klatkę co sekundę do prywatnego katalogu aplikacji na telefonie i zachowuje najwyżej 300 ostatnich klatek (około 5 minut); starsze usuwa. Tryb analizy AI wysyła najnowszą klatkę do ChatGPT co 5 sekund, dopóki go nie zatrzymasz. Z mikrofonu można równocześnie rozmawiać z Milo. Obraz jest przesyłany do OpenAI tylko przy analizie AI albo wysłaniu zdjęcia; kamera nie działa w tle.

Jeśli systemowy DNS nie znajduje hosta logowania, Milo ponawia zapytanie przez DNS-over-HTTPS, łącząc się z resolverem po jego znanym adresie IP. Weryfikacja certyfikatu TLS i nazwy resolvera pozostaje włączona. Następnie Milo może spróbować publicznego DNS przez UDP. Jeśli sieć blokuje także zapasowe połączenia, sprawdź dostęp do internetu i filtry sieciowe.

## Budowanie APK

Wymagane: Android SDK, JDK 17 i Gradle 8.11.1.

```powershell
gradle assembleDebug
```

APK debug znajduje się w `app/build/outputs/apk/debug/app-debug.apk`. Aplikacja nie jest publikowana w Google Play; instalacja APK wymaga zgody Androida na instalowanie aplikacji z tego źródła.

## Granice

- To aplikacja Android, która łączy się bezpośrednio z usługą OpenAI. Do tego wariantu nie jest potrzebny serwer Render ani własny klucz API.
- Funkcja AI wymaga internetu, aktywnej zgody w ChatGPT oraz dostępnego limitu planu.
- Osobna pamięć Milo jest lokalna dla tego telefonu; nie jest trenowaniem modelu ani dostępem do historii ChatGPT.
- Aktualizacje APK wymagają osobnego kanału dystrybucji i podpisu tym samym kluczem.
