/**
 * The BSD License
 *
 * Copyright (c) 2010-2018 RIPE NCC
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *   - Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimer.
 *   - Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *   - Neither the name of the RIPE NCC nor the names of its contributors may be
 *     used to endorse or promote products derived from this software without
 *     specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package net.ripe.rpki.validator3.api.rpkirepositories;

import lombok.extern.slf4j.Slf4j;
import net.ripe.rpki.validator3.api.Api;
import net.ripe.rpki.validator3.api.ApiResponse;
import net.ripe.rpki.validator3.api.Metadata;
import net.ripe.rpki.validator3.api.Paging;
import net.ripe.rpki.validator3.api.SearchTerm;
import net.ripe.rpki.validator3.api.Sorting;
import net.ripe.rpki.validator3.storage.data.Key;
import net.ripe.rpki.validator3.storage.data.RpkiRepository;
import net.ripe.rpki.validator3.storage.lmdb.Lmdb;
import net.ripe.rpki.validator3.storage.stores.RpkiRepositories;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.hateoas.Links;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.springframework.hateoas.mvc.ControllerLinkBuilder.methodOn;

@RestController
@RequestMapping(path = "/api/rpki-repositories", produces = {Api.API_MIME_TYPE, "application/json"})
@Slf4j
public class RpkiRepositoriesController {

    private final RpkiRepositories rpkiRepositories;
    private final Lmdb lmdb;

    @Autowired
    public RpkiRepositoriesController(RpkiRepositories rpkiRepositories, Lmdb lmdb) {
        this.rpkiRepositories = rpkiRepositories;
        this.lmdb = lmdb;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Stream<RpkiRepositoryResource>>> list(
            @RequestParam(name = "status", required = false) RpkiRepository.Status status,
            @RequestParam(name = "ta", required = false) Long taId,
            @RequestParam(name = "startFrom", defaultValue = "0") long startFrom,
            @RequestParam(name = "pageSize", defaultValue = "20") long pageSize,
            @RequestParam(name = "search", defaultValue = "", required = false) String searchString,
            @RequestParam(name = "sortBy", defaultValue = "location") String sortBy,
            @RequestParam(name = "sortDirection", defaultValue = "asc") String sortDirection,
            @RequestParam(name = "hideChildrenOfDownloadedParent", defaultValue = "true") boolean hideChildrenOfDownloadedParent
    ) {
        final SearchTerm searchTerm = StringUtils.isNotBlank(searchString) ? new SearchTerm(searchString) : null;
        final Sorting sorting = Sorting.parse(sortBy, sortDirection);
        final Paging paging = Paging.of(startFrom, pageSize);

        return lmdb.readTx(tx -> {
            final Key taKey = taId == null ? null : Key.of(taId);
            final List<RpkiRepository> repositories = rpkiRepositories.findAll(tx,
                    status, taKey, hideChildrenOfDownloadedParent, searchTerm, sorting, paging)
                    .collect(Collectors.toList());

            final int totalSize = (int) rpkiRepositories.countAll(tx, status, taKey, hideChildrenOfDownloadedParent, searchTerm);
            final Links links = Paging.links(
                    startFrom, pageSize, totalSize,
                    (sf, ps) -> methodOn(RpkiRepositoriesController.class).list(status, taId, sf, ps, searchString, sortBy, sortDirection, hideChildrenOfDownloadedParent));

            final Stream<RpkiRepositoryResource> data = repositories.stream().map(RpkiRepositoryResource::of);

            return ResponseEntity.ok(ApiResponse.<Stream<RpkiRepositoryResource>>builder()
                    .data(data)
                    .links(links)
                    .metadata(Metadata.of(totalSize))
                    .build());
        });
    }

    @GetMapping(path = "/{id}")
    public ResponseEntity<ApiResponse<RpkiRepositoryResource>> get(@PathVariable long id) {
        return lmdb.readTx(tx -> rpkiRepositories.get(tx, Key.of(id)))
                .map(r -> ResponseEntity.ok(ApiResponse.data(RpkiRepositoryResource.of(r))))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping(path = "/statuses/{taId}")
    public ApiResponse<RepositoriesStatus> repositories(
            @PathVariable long taId,
            @RequestParam(name = "hideChildrenOfDownloadedParent", defaultValue = "true") boolean hideChildrenOfDownloadedParent
    ) {
        final Map<RpkiRepository.Status, Long> counts = lmdb.readTx(tx ->
                rpkiRepositories.countByStatus(tx, Key.of(taId), hideChildrenOfDownloadedParent));

        return ApiResponse.<RepositoriesStatus>builder().data(RepositoriesStatus.of(
                counts.getOrDefault(RpkiRepository.Status.DOWNLOADED, 0L).intValue(),
                counts.getOrDefault(RpkiRepository.Status.PENDING, 0L).intValue(),
                counts.getOrDefault(RpkiRepository.Status.FAILED, 0L).intValue()
        )).build();
    }

    @DeleteMapping(path = "/{id}")
    public ResponseEntity<?> delete(@PathVariable long id) {
        lmdb.writeTx0(tx -> rpkiRepositories.remove(tx, Key.of(id)));
        return ResponseEntity.noContent().build();
    }
}
