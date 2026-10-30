package app.houselights.providers.local

import app.houselights.core.ArtworkKind

/** A saga's pictures without the user's choice, as its walls and its page show them ([LocalProvider], [LocalEditor]). */
internal object SagaPictures {
    /**
     * Saga [saga]'s Automatique of [kind], its poster or its backdrop: TMDB's picture of it, else its [first] film's in
     * release order ([LibraryDao.collectionMembers]) as that film shows it.
     */
    fun automatic(saga: CollectionEntity, kind: ArtworkKind, first: CollectionMember?): String? =
        if (kind == ArtworkKind.POSTER) {
            automatic(saga, kind, first?.userPosterPath, first?.posterPath)
        } else {
            automatic(saga, kind, first?.userBackdropPath, first?.backdropPath)
        }

    /** [automatic] of [first] read whole ([LibraryDao.collectionFilms], in the same order). */
    fun automatic(saga: CollectionEntity, kind: ArtworkKind, first: ItemEntity?): String? =
        if (kind == ArtworkKind.POSTER) {
            automatic(saga, kind, first?.userPosterPath, first?.posterPath)
        } else {
            automatic(saga, kind, first?.userBackdropPath, first?.backdropPath)
        }

    private fun automatic(saga: CollectionEntity, kind: ArtworkKind, chosen: String?, own: String?): String? =
        (if (kind == ArtworkKind.POSTER) saga.posterPath else saga.backdropPath) ?: LocalProvider.shown(chosen, own)
}
