package app.houselights.testing

/** [count] subfolders' names, as a folder's level lists them: "Dossier 1", "Dossier 2"… */
fun folderNames(count: Int): List<String> = List(count) { "Dossier ${it + 1}" }
