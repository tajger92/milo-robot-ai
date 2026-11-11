# Milo — robot AI na Androida

Milo to prywatnie instalowana aplikacja Android z polskim czatem AI, przyjazną twarzą, osobną pamięcią i kamerą uruchamianą przez użytkownika.

## AI przez plan ChatGPT

Milo loguje się do ChatGPT przez oficjalny przepływ **Sign in with ChatGPT**. Po zalogowaniu używa autoryzowanych żądań Responses API bez klucza API. Wymaga to otwartego kodu aplikacji i zgody użytkownika w oknie OpenAI. Aplikacja nie czyta Twoich rozmów w ChatGPT.

Plan ChatGPT Plus może być używany przez kwalifikowane aplikacje open source. Limity użycia są wspólne z innymi aplikacjami korzystającymi z planu; nie tworzą osobnego limitu dla Milo. Dostęp i funkcje zależą od konta oraz bieżących limitów. Szczegóły: [Sign in with ChatGPT](https://developers.openai.com/siwc/quickstart) i [ograniczenia podglądu](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations).

Milo trzyma pamięć i historię rozmów lokalnie na telefonie. Pamięć przekazuje jako kontekst do kolejnych odpowiedzi. Napisz „Zapamiętaj, że…” albo użyj ikony mózgu, by ją wyświetlić i wyczyścić. Tokeny OAuth są szyfrowane lokalnie za pomocą Android Keystore.

## Kamera

Podgląd działa tylko po naciśnięciu ikony aparatu. Zdjęcie trafia do AI po wysłaniu wiadomości. Tryb analizy na żywo wysyła kadr co 5 sekund, dopóki go nie zatrzymasz. Obraz jest przesyłany do OpenAI, dlatego używaj tego trybu świadomie. Kamera nie działa w tle.

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
