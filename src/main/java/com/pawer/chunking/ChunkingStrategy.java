package com.pawer.chunking;

public enum ChunkingStrategy {

    /**
     * Podział na chunki o stałej liczbie tokenów z nakładką (overlap).
     * Dobry do: jednorodnych dokumentów, szybkiego prototypowania.
     */
    TOKEN,

    /**
     * Podział po akapitach wykrytych przez PDFBox (wymaga TOC w PDF).
     * Dobry do: raportów, dokumentacji z nagłówkami.
     */
    PARAGRAPH,

    /**
     * Podział po zdaniach z grupowaniem do max rozmiaru.
     * Dobry do: artykułów, tekstów narracyjnych.
     */
    SENTENCE,

    /**
     * Hierarchiczny podział — małe chunki do retrieval,
     * duże (parent) zwracane jako kontekst dla LLM.
     * Dobry do: długich dokumentów gdzie liczy się precyzja + kontekst.
     */
    HIERARCHICAL,

    /**
     * Podział semantyczny przez LLM — atomowe twierdzenia/fakty.
     * Dobry do: baz wiedzy, FAQ, maksymalna jakość retrieval.
     * Uwaga: wolny (wymaga wywołania LLM per chunk).
     */
    SEMANTIC
}