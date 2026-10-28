package org.rust.cargo.project.model.impl

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import org.rust.cargo.project.workspace.CargoWorkspace
import org.rust.cargo.project.workspace.CargoWorkspaceData
import org.rust.cargo.project.workspace.PackageOrigin
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/**
 * The Marketplace Rust descriptor names this test service but does not ship its class.
 * Use the real Cargo service with fixture metadata in our platform tests; no toolchain is needed.
 */
class TestCargoProjectsServiceImpl(project: Project) : CargoProjectsServiceImpl(project) {
    /** Install metadata directly, keeping resolution identical to an imported Cargo project. */
    fun configure(crateRoot: VirtualFile) {
        val edition = CargoWorkspace.Edition.EDITION_2021
        val root = crateRoot.parent
        val manifest = Path.of(root.path, "Cargo.toml")
        val target = CargoWorkspaceData.Target(crateRoot.url, "sample", CargoWorkspace.TargetKind.Bin, edition, false, emptyList())
        val pkg = CargoWorkspaceData.Package(
            id = "sample", contentRootUrl = root.url, name = "sample", version = "0.1.0",
            targets = listOf(target), source = null, origin = PackageOrigin.WORKSPACE, edition = edition,
            features = emptyMap(), enabledFeatures = emptySet(), cfgOptions = null, env = emptyMap(), outDirUrl = null,
            categories = emptySet(),
        )
        val workspace = CargoWorkspace.deserialize(manifest, CargoWorkspaceData(listOf(pkg), emptyMap(), emptyMap(), root.url))
        modifyProjects {
            CompletableFuture.completedFuture(listOf(CargoProjectImpl(manifest, this, rawWorkspace = workspace)))
        }.join()
    }
}
