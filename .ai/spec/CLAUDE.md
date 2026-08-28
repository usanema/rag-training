# Spec-Driven Development — zasady pracy

## Co to jest spec-driven?

Przed implementacją każdej nietrywialnej funkcji tworzymy spec — dokument opisujący **co** budujemy i **dlaczego**, zanim piszemy kod. Spec jest kontraktem między nami: po akceptacji przez użytkownika implementuję dokładnie to, co w nim opisano.

## Kiedy tworzyć spec?

- Nowa funkcja wymagająca decyzji architektonicznych
- Integracja z zewnętrzną biblioteką/serwisem
- Zmiana istniejącego flow (request pipeline, konfiguracja, API contract)
- Cokolwiek, gdzie "jak to zrobić" nie jest oczywiste

Bugfixy i drobne zmiany — bez spec, bezpośrednio do kodu.

## Format pliku

```
DD_MM_RRRR_<kebab-case-tytul>.md
```

Przykład: `28_08_2026_query-routing-google-adk.md`

## Struktura spec (wymagane sekcje)

```markdown
# [Tytuł funkcji]

## Status
Draft | W review | Zaakceptowana | Zaimplementowana

## Cel
Jeden akapit: co chcemy osiągnąć i dlaczego.

## Kontekst
Jak wygląda obecne rozwiązanie. Co nie działa lub czego brakuje.

## Wymagania funkcjonalne
Lista wymagań w formie "System musi..." lub "Użytkownik może...".

## Wymagania niefunkcjonalne
Latency, niezawodność, kompatybilność wsteczna, etc.

## Proponowane rozwiązanie
Opis high-level podejścia. Alternatywy i dlaczego zostały odrzucone.

## Architektura / Design
Diagramy (ASCII), sequence flow, nowe klasy/interfejsy, zmiany w istniejących.

## Zależności
Nowe zależności Maven/npm, zewnętrzne serwisy, konfiguracja.

## Zmiany w kodzie
Konkretne pliki do zmodyfikowania lub dodania. Bez kodu — tylko "co i gdzie".

## Plan implementacji
Numerowana lista kroków. Kolejność ma znaczenie.

## Scenariusze testowe
Jak ręcznie zweryfikować że działa. Przypadki brzegowe.

## Pytania otwarte
Rzeczy do decyzji przed lub w trakcie implementacji.
```

## Workflow

```
1. DRAFT    — Claude tworzy spec na podstawie opisu użytkownika
2. REVIEW   — Wspólnie przeglądamy: użytkownik zadaje pytania, proponuje zmiany
3. AKCEPTACJA — Użytkownik mówi "implementuj" lub "OK"
4. IMPLEMENT — Claude koduje zgodnie ze spec, bez zbaczania z kursu
5. DONE     — Status w pliku zmieniony na "Zaimplementowana"
```

## Zasady

- **Spec jest źródłem prawdy** — jeśli coś nie jest w spec, nie implementuję tego bez pytania
- **Zmiany po akceptacji** — jeśli w trakcie implementacji pojawi się problem ze spec, zgłaszam to zamiast samodzielnie zmieniać kierunek
- **Pytania otwarte** — spec może być zaakceptowana z nierozwiązanymi pytaniami; rozstrzygamy je w trakcie implementacji
- **Nie commitujemy spec** — pliki `.ai/` są w `.gitignore` lub to lokalna dokumentacja (zależnie od preferencji projektu)
