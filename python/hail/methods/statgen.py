from hail.api2.matrixtable import MatrixTable, Table
from hail.expr.expression import *
from hail.genetics import KinshipMatrix
from hail.genetics.ldMatrix import LDMatrix
from hail.linalg import BlockMatrix
from hail.typecheck import *
from hail.utils import wrap_to_list, new_temp_file, info
from hail.utils.java import handle_py4j
from .misc import require_biallelic
from hail.expr import functions
import hail.expr.aggregators as agg


@typecheck(dataset=MatrixTable,
           ys=oneof(Expression, listof(Expression)),
           x=Expression,
           covariates=listof(Expression),
           root=strlike,
           block_size=integral)
def linreg(dataset, ys, x, covariates=[], root='linreg', block_size=16):
    """For each row, test a derived input variable for association with response variables using linear regression.

    Examples
    --------

    >>> dataset_result = methods.linreg(dataset, [dataset.pheno.height], dataset.GT.num_alt_alleles(),
    ...                                 covariates=[dataset.pheno.age, dataset.pheno.isFemale])

    Warning
    -------
    :meth:`linreg` considers the same set of columns (i.e., samples, points) for every response variable and row,
    namely those columns for which **all** response variables and covariates are defined.
    For each row, missing values of ``x`` are mean-imputed over these columns.

    Notes
    -----

    With the default root, the following row-indexed fields are added.
    The indexing of the array fields corresponds to that of ``ys``.

    - **linreg.nCompleteSamples** (*Int32*) -- number of columns used
    - **linreg.AC** (*Float64*) -- sum of input values ``x``
    - **linreg.ytx** (*Array[Float64]*) -- array of dot products of each response vector ``y`` with the input vector ``x``
    - **linreg.beta** (*Array[Float64]*) -- array of fit effect coefficients of ``x``, :math:`\hat\\beta_1` below
    - **linreg.se** (*Array[Float64]*) -- array of estimated standard errors, :math:`\widehat{\mathrm{se}}_1`
    - **linreg.tstat** (*Array[Float64]*) -- array of :math:`t`-statistics, equal to :math:`\hat\\beta_1 / \widehat{\mathrm{se}}_1`
    - **linreg.pval** (*Array[Float64]*) -- array of :math:`p`-values

    In the statistical genetics example above, the input variable ``x`` encodes genotype
    as the number of alternate alleles (0, 1, or 2). For each variant (row), genotype is tested for association
    with height controlling for age and sex, by fitting the linear regression model:

    .. math::

        \mathrm{height} = \\beta_0 + \\beta_1 \, \mathrm{genotype} + \\beta_2 \, \mathrm{age} + \\beta_3 \, \mathrm{isFemale} + \\varepsilon, \quad \\varepsilon \sim \mathrm{N}(0, \sigma^2)

    Boolean covariates like :math:`\mathrm{isFemale}` are encoded as 1 for true and 0 for false.
    The null model sets :math:`\\beta_1 = 0`.

    The standard least-squares linear regression model is derived in Section
    3.2 of `The Elements of Statistical Learning, 2nd Edition
    <http://statweb.stanford.edu/~tibs/ElemStatLearn/printings/ESLII_print10.pdf>`__. See
    equation 3.12 for the t-statistic which follows the t-distribution with
    :math:`n - k - 2` degrees of freedom, under the null hypothesis of no
    effect, with :math:`n` samples and :math:`k` covariates in addition to
    ``x`` and the intercept.

    Parameters
    ----------
    ys : :obj:`list` of :class:`hail.expr.expression.Expression`
        One or more response expressions.
    x : :class:`hail.expr.expression.Expression`
        Input variable.
    covariates : :obj:`list` of :class:`hail.expr.expression.Expression`
        Covariate expressions.
    root : :obj:`str`
        Name of resulting row-indexed field.
    block_size : :obj:`int`
        Number of row regressions to perform simultaneously per core. Larger blocks
        require more memory but may improve performance.

    Returns
    -------
    :class:`.MatrixTable`
        Dataset with regression results in a new row-indexed field.
    """

    all_exprs = [x]

    ys = wrap_to_list(ys)

    # x is entry-indexed
    analyze('linreg/x', x, dataset._entry_indices)

    # ys and covariates are col-indexed
    ys = wrap_to_list(ys)
    for e in ys:
        all_exprs.append(e)
        analyze('linreg/ys', e, dataset._col_indices)
    for e in covariates:
        all_exprs.append(e)
        analyze('linreg/covariates', e, dataset._col_indices)

    base, cleanup = dataset._process_joins(*all_exprs)

    jm = base._jvds.linreg(
        jarray(Env.jvm().java.lang.String, [y._ast.to_hql() for y in ys]),
        x._ast.to_hql(),
        jarray(Env.jvm().java.lang.String, [cov._ast.to_hql() for cov in covariates]),
        'va.`{}`'.format(root),
        block_size
    )

    return cleanup(MatrixTable(jm))


@handle_py4j
@require_biallelic
@typecheck(dataset=MatrixTable, force_local=bool)
def ld_matrix(dataset, force_local=False):
    """Computes the linkage disequilibrium (correlation) matrix for the variants in this VDS.

    .. include:: ../_templates/req_tvariant.rst

    .. include:: ../_templates/req_biallelic.rst

    .. testsetup::

        dataset = vds.annotate_samples_expr('sa = drop(sa, qc)').to_hail2()
        from hail.methods import ld_matrix

    **Examples**

    >>> ld_matrix = ld_matrix(dataset)

    **Notes**

    Each entry (i, j) in the LD matrix gives the :math:`r` value between variants i and j, defined as
    `Pearson's correlation coefficient <https://en.wikipedia.org/wiki/Pearson_correlation_coefficient>`__
    :math:`\\rho_{x_i,x_j}` between the two genotype vectors :math:`x_i` and :math:`x_j`.

    .. math::

        \\rho_{x_i,x_j} = \\frac{\\mathrm{Cov}(X_i,X_j)}{\\sigma_{X_i} \\sigma_{X_j}}

    Also note that variants with zero variance (:math:`\\sigma = 0`) will be dropped from the matrix.

    .. caution::

        The matrix returned by this function can easily be very large with most entries near zero
        (for example, entries between variants on different chromosomes in a homogenous population).
        Most likely you'll want to reduce the number of variants with methods like
        :py:meth:`.sample_variants`, :py:meth:`.filter_variants_expr`, or :py:meth:`.ld_prune` before
        calling this unless your dataset is very small.

    :param dataset: Variant-keyed dataset.
    :type dataset: :py:class:`.MatrixTable`

    :param bool force_local: If true, the LD matrix is computed using local matrix multiplication on the Spark driver.
        This may improve performance when the genotype matrix is small enough to easily fit in local memory.
        If false, the LD matrix is computed using distributed matrix multiplication if the number of entries
        exceeds :math:`5000^2` and locally otherwise.

    :return: Matrix of r values between pairs of variants.
    :rtype: :py:class:`.LDMatrix`
    """

    jldm = Env.hail().methods.LDMatrix.apply(dataset._jvds, force_local)
    return LDMatrix(jldm)


@handle_py4j
@require_biallelic
@typecheck(dataset=MatrixTable,
           k=integral,
           compute_loadings=bool,
           as_array=bool)
def hwe_normalized_pca(dataset, k=10, compute_loadings=False, as_array=False):
    """Run principal component analysis (PCA) on the Hardy-Weinberg-normalized call matrix.

    Examples
    --------

    >>> eigenvalues, scores, loadings = methods.hwe_normalized_pca(dataset, k=5)

    Notes
    -----
    Variants that are all homozygous reference or all homozygous variant are removed before evaluation.

    Parameters
    ----------
    dataset : :class:`.MatrixTable`
        Dataset.
    k : :obj:`int`
        Number of principal components.
    compute_loadings : :obj:`bool`
        If ``True``, compute row loadings.
    as_array : :obj:`bool`
        If ``True``, return scores and loadings as an array field. If ``False``, return
        one field per element (`PC1`, `PC2`, ... `PCk`).

    Returns
    -------
    (:obj:`list` of :obj:`float`, :class:`.Table`, :class:`.Table`)
        List of eigenvalues, table with column scores, table with row loadings.
    """

    dataset = dataset.annotate_rows(AC=agg.sum(dataset.GT.num_alt_alleles()),
                                    n_called=agg.count_where(functions.is_defined(dataset.GT)))
    dataset = dataset.filter_rows((dataset.AC > 0) & (dataset.AC < 2 * dataset.n_called)).persist()

    n_variants = dataset.count_rows()
    if n_variants == 0:
        raise FatalError(
            "Cannot run PCA: found 0 variants after filtering out monomorphic sites.")
    info("Running PCA using {} variants.".format(n_variants))

    entry_expr = functions.bind(
        dataset.AC / dataset.n_called,
        lambda mean_gt: functions.cond(functions.is_defined(dataset.GT),
                                       (dataset.GT.num_alt_alleles() - mean_gt) /
                                       functions.sqrt(mean_gt * (2 - mean_gt) * n_variants / 2),
                                       0))
    result = pca(entry_expr,
                 k,
                 compute_loadings,
                 as_array)
    dataset.unpersist()
    return result


@handle_py4j
@typecheck(entry_expr=expr_numeric,
           k=integral,
           compute_loadings=bool,
           as_array=bool)
def pca(entry_expr, k=10, compute_loadings=False, as_array=False):
    """Run principal Component Analysis (PCA) on a matrix table, using `entry_expr` as the numerical entry.

    Examples
    --------

    Compute the top 2 principal component scores and eigenvalues of the call missingness matrix.

    >>> eigenvalues, scores, _ = methods.pca(functions.is_defined(dataset.GT).to_int32(),
    ...                                      k=2)

    Notes
    -----

    PCA computes the SVD

    .. math::

      M = USV^T

    where columns of :math:`U` are left singular vectors (orthonormal in
    :math:`\mathbb{R}^n`), columns of :math:`V` are right singular vectors
    (orthonormal in :math:`\mathbb{R}^m`), and :math:`S=\mathrm{diag}(s_1, s_2,
    \ldots)` with ordered singular values :math:`s_1 \ge s_2 \ge \cdots \ge 0`.
    Typically one computes only the first :math:`k` singular vectors and values,
    yielding the best rank :math:`k` approximation :math:`U_k S_k V_k^T` of
    :math:`M`; the truncations :math:`U_k`, :math:`S_k` and :math:`V_k` are
    :math:`n \\times k`, :math:`k \\times k` and :math:`m \\times k`
    respectively.

    From the perspective of the samples or rows of :math:`M` as data,
    :math:`V_k` contains the variant loadings for the first :math:`k` PCs while
    :math:`MV_k = U_k S_k` contains the first :math:`k` PC scores of each
    sample. The loadings represent a new basis of features while the scores
    represent the projected data on those features. The eigenvalues of the GRM
    :math:`MM^T` are the squares of the singular values :math:`s_1^2, s_2^2,
    \ldots`, which represent the variances carried by the respective PCs. By
    default, Hail only computes the loadings if the ``loadings`` parameter is
    specified.

    Note
    ----
    In PLINK/GCTA the GRM is taken as the starting point and it is
    computed slightly differently with regard to missing data. Here the
    :math:`ij` entry of :math:`MM^T` is simply the dot product of rows :math:`i`
    and :math:`j` of :math:`M`; in terms of :math:`C` it is

    .. math::

      \\frac{1}{m}\sum_{l\in\mathcal{C}_i\cap\mathcal{C}_j}\\frac{(C_{il}-2p_l)(C_{jl} - 2p_l)}{2p_l(1-p_l)}

    where :math:`\mathcal{C}_i = \{l \mid C_{il} \\text{ is non-missing}\}`. In
    PLINK/GCTA the denominator :math:`m` is replaced with the number of terms in
    the sum :math:`\\lvert\mathcal{C}_i\cap\\mathcal{C}_j\\rvert`, i.e. the
    number of variants where both samples have non-missing genotypes. While this
    is arguably a better estimator of the true GRM (trading shrinkage for
    noise), it has the drawback that one loses the clean interpretation of the
    loadings and scores as features and projections.

    Separately, for the PCs PLINK/GCTA output the eigenvectors of the GRM; even
    ignoring the above discrepancy that means the left singular vectors
    :math:`U_k` instead of the component scores :math:`U_k S_k`. While this is
    just a matter of the scale on each PC, the scores have the advantage of
    representing true projections of the data onto features with the variance of
    a score reflecting the variance explained by the corresponding feature. (In
    PC bi-plots this amounts to a change in aspect ratio; for use of PCs as
    covariates in regression it is immaterial.)

    Scores are stored in a :class:`.Table` with the following fields:

     - **s**: Column key of the dataset.

     - **pcaScores**: The principal component scores. This can have two different
       structures, depending on the value of the `as_array` flag.

    If `as_array` is ``False`` (default), then `pcaScores` is a ``Struct`` with
    fields `PC1`, `PC2`, etc. If `as_array` is ``True``, then `pcaScores` is a
    field of type ``Array[Float64]`` containing the principal component scores.

    Loadings are stored in a :class:`.Table` with a structure similar to the scores
    table:

     - **v**: Row key of the dataset.

     - **pcaLoadings**: Row loadings (same type as the scores)

    Parameters
    ----------
    dataset : :class:`.MatrixTable`
        Dataset.
    entry_expr : :class:`.Expression`
        Numeric expression for matrix entries.
    k : :obj:`int`
        Number of principal components.
    compute_loadings : :obj:`bool`
        If ``True``, compute row loadings.
    as_array : :obj:`bool`
        If ``True``, return scores and loadings as an array field. If ``False``, return
        one field per element (`PC1`, `PC2`, ... `PCk`).

    Returns
    -------
    (:obj:`list` of :obj:`float`, :class:`.Table`, :class:`.Table`)
        List of eigenvalues, table with column scores, table with row loadings.
    """
    source = entry_expr._indices.source
    if not isinstance(source, MatrixTable):
        raise ValueError("Expect an expression of 'MatrixTable', found {}".format(
            "expression of '{}'".format(source.__class__) if source is not None else 'scalar expression'))
    dataset = source
    base, _ = dataset._process_joins(entry_expr)
    analyze('pca', entry_expr, dataset._entry_indices)

    r = Env.hail().methods.PCA.apply(dataset._jvds, to_expr(entry_expr)._ast.to_hql(), k, compute_loadings, as_array)
    scores = Table(Env.hail().methods.PCA.scoresTable(dataset._jvds, as_array, r._2()))
    loadings = from_option(r._3())
    if loadings:
        loadings = Table(loadings)
    return (jiterable_to_list(r._1()), scores, loadings)

@handle_py4j
@typecheck(dataset=MatrixTable,
           fraction=numeric,
           seed=integral)
def sample_rows(dataset, fraction, seed=1):
    """Downsample rows to a given fraction of the dataset.

    Examples
    --------

    >>> small_dataset = methods.sample_rows(dataset, 0.01)

    Notes
    -----

    This method may not sample exactly ``(fraction * n_rows)`` rows from
    the dataset.

    Parameters
    ----------
    dataset : :class:`.MatrixTable`
        Dataset to sample from.
    fraction : :obj:`float`
        (Expected) fraction of rows to keep.
    seed : :obj:`int`
        Random seed.

    Returns
    ------
    :class:`.MatrixTable`
        Downsampled matrix table.
    """

    return MatrixTable(dataset._jvds.sampleVariants(fraction, seed))

@handle_py4j
@typecheck(ds=MatrixTable,
           keep_star=bool,
           left_aligned=bool)
def split_multi_hts(ds, keep_star=False, left_aligned=False):
    """Split multiallelic variants for HTS :meth:`.MatrixTable.entry_schema`:

    .. code-block:: text

      Struct {
        GT: Call,
        AD: Array[!Int32],
        DP: Int32,
        GQ: Int32,
        PL: Array[!Int32].
      }

    For generic genotype schema, use :meth:`methods.split_multi`.

    Examples
    --------

    >>> methods.split_multi_hts(dataset).write('output/split.vds')

    Notes
    -----

    We will explain by example. Consider a hypothetical 3-allelic
    variant:

    .. code-block:: text

      A   C,T 0/2:7,2,6:15:45:99,50,99,0,45,99

    split_multi will create two biallelic variants (one for each
    alternate allele) at the same position

    .. code-block:: text

      A   C   0/0:13,2:15:45:0,45,99
      A   T   0/1:9,6:15:50:50,0,99

    Each multiallelic `GT` field is downcoded once for each alternate allele. A
    call for an alternate allele maps to 1 in the biallelic variant
    corresponding to itself and 0 otherwise. For example, in the example above,
    0/2 maps to 0/0 and 0/1. The genotype 1/2 maps to 0/1 and 0/1.

    The biallelic alt `AD` entry is just the multiallelic `AD` entry
    corresponding to the alternate allele. The ref AD entry is the sum of the
    other multiallelic entries.

    The biallelic `DP` is the same as the multiallelic `DP`.

    The biallelic `PL` entry for a genotype g is the minimum over `PL` entries
    for multiallelic genotypes that downcode to g. For example, the `PL` for (A,
    T) at 0/1 is the minimum of the PLs for 0/1 (50) and 1/2 (45), and thus 45.

    Fixing an alternate allele and biallelic variant, downcoding gives a map
    from multiallelic to biallelic alleles and genotypes. The biallelic `AD` entry
    for an allele is just the sum of the multiallelic `AD` entries for alleles
    that map to that allele. Similarly, the biallelic `PL` entry for a genotype is
    the minimum over multiallelic `PL` entries for genotypes that map to that
    genotype.

    `GQ` is recomputed from `PL`.

    Here is a second example for a het non-ref

    .. code-block:: text

      A   C,T 1/2:2,8,6:16:45:99,50,99,45,0,99

    splits as

    .. code-block:: text

      A   C   0/1:8,8:16:45:45,0,99
      A   T   0/1:10,6:16:50:50,0,99

    **VCF Info Fields**

    Hail does not split fields in the info field. This means that if a
    multiallelic site with `info.AC` value ``[10, 2]`` is split, each split
    site will contain the same array ``[10, 2]``. The provided allele index
    field `aIndex` can be used to select the value corresponding to the split
    allele's position:

    >>> ds = methods.split_multi_hts(dataset)
    >>> ds = ds.filter_rows(ds.info.AC[ds.aIndex - 1] < 10, keep = False)

    VCFs split by Hail and exported to new VCFs may be
    incompatible with other tools, if action is not taken
    first. Since the "Number" of the arrays in split multiallelic
    sites no longer matches the structure on import ("A" for 1 per
    allele, for example), Hail will export these fields with
    number ".".

    If the desired output is one value per site, then it is
    possible to use annotate_variants_expr to remap these
    values. Here is an example:

    >>> ds = methods.split_multi_hts(dataset)
    >>> ds = ds.annotate_rows(info = Struct(AC=ds.info.AC[ds.aIndex - 1], **ds.info)) # doctest: +SKIP
    >>> methods.export_vcf(ds, 'output/export.vcf') # doctest: +SKIP

    The info field AC in *data/export.vcf* will have ``Number=1``.

    **New Fields**

    :meth:`hail.methods.split_multi_hts` adds the following fields:

     - `wasSplit` (*Boolean*) -- ``True`` if this variant was originally
       multiallelic, otherwise ``False``.

     - `aIndex` (*Int*) -- The original index of this alternate allele in the
       multiallelic representation (NB: 1 is the first alternate allele or the
       only alternate allele in a biallelic variant). For example, 1:100:A:T,C
       splits into two variants: 1:100:A:T with ``aIndex = 1`` and 1:100:A:C
       with ``aIndex = 2``.

    Parameters
    ----------
    keep_star : :obj:`bool`
        Do not filter out * alleles.
    left_aligned : :obj:`bool`
        If ``True``, variants are assumed to be left
        aligned and have unique loci. This avoids a shuffle. If the assumption
        is violated, an error is generated.

    Returns
    -------
    :class:`.MatrixTable`
        A biallelic variant dataset.

    """

    variant_expr = 'va.aIndex = aIndex, va.wasSplit = wasSplit'
    genotype_expr = '''
g = let
  newgt = downcode(g.GT, aIndex) and
  newad = if (isDefined(g.AD))
      let sum = g.AD.sum() and adi = g.AD[aIndex] in [sum - adi, adi]
    else
      NA: Array[Int] and
  newpl = if (isDefined(g.PL))
      range(3).map(i => range(g.PL.length).filter(j => downcode(Call(j), aIndex) == Call(i)).map(j => g.PL[j]).min())
    else
      NA: Array[Int] and
  newgq = gqFromPL(newpl)
in { GT: newgt, AD: newad, DP: g.DP, GQ: newgq, PL: newpl }
'''
    jds = scala_object(Env.hail().methods, 'SplitMulti').apply(
        ds._jvds, variant_expr, genotype_expr, keep_star, left_aligned)
    return MatrixTable(jds)

@require_biallelic
@typecheck(dataset=MatrixTable)
def grm(dataset):
    """Compute the Genetic Relatedness Matrix (GRM).

    .. include:: ../_templates/req_tvariant.rst
    .. include:: ../_templates/req_biallelic.rst

    Examples
    --------

    >>> km = methods.grm(dataset)

    Notes
    -----

    The genetic relationship matrix (GRM) :math:`G` encodes genetic correlation
    between each pair of samples. It is defined by :math:`G = MM^T` where
    :math:`M` is a standardized version of the genotype matrix, computed as
    follows. Let :math:`C` be the :math:`n \\times m` matrix of raw genotypes
    in the variant dataset, with rows indexed by :math:`n` samples and columns
    indexed by :math:`m` bialellic autosomal variants; :math:`C_{ij}` is the
    number of alternate alleles of variant :math:`j` carried by sample
    :math:`i`, which can be 0, 1, 2, or missing. For each variant :math:`j`,
    the sample alternate allele frequency :math:`p_j` is computed as half the
    mean of the non-missing entries of column :math:`j`. Entries of :math:`M`
    are then mean-centered and variance-normalized as

    .. math::

        M_{ij} = \\frac{C_{ij}-2p_j}{\sqrt{2p_j(1-p_j)m}},

    with :math:`M_{ij} = 0` for :math:`C_{ij}` missing (i.e. mean genotype
    imputation). This scaling normalizes genotype variances to a common value
    :math:`1/m` for variants in Hardy-Weinberg equilibrium and is further
    motivated in the paper `Patterson, Price and Reich, 2006
    <http://journals.plos.org/plosgenetics/article?id=10.1371/journal.pgen.0020190>`__.
    (The resulting amplification of signal from the low end of the allele
    frequency spectrum will also introduce noise for rare variants; common
    practice is to filter out variants with minor allele frequency below some
    cutoff.) The factor :math:`1/m` gives each sample row approximately unit
    total variance (assuming linkage equilibrium) so that the diagonal entries
    of the GRM are approximately 1. Equivalently,

    .. math::

        G_{ik} = \\frac{1}{m} \\sum_{j=1}^m \\frac{(C_{ij}-2p_j)(C_{kj}-2p_j)}{2 p_j (1-p_j)}

    Warning
    -------
    Since Hardy-Weinberg normalization cannot be applied to variants that
    contain only reference alleles or only alternate alleles, all such variants
    are removed prior to calcularing the GRM.

    Parameters
    ----------
    dataset : :class:`.MatrixTable`
        Dataset to sample from.

    Returns
    -------
    :class:`genetics.KinshipMatrix`
        Genetic Relatedness Matrix for all samples.
    :rtype:
    """

    dataset = dataset.annotate_rows(AC=agg.sum(dataset.GT.num_alt_alleles()),
                                    n_called=agg.count_where(functions.is_defined(dataset.GT)))
    dataset = dataset.filter_rows((dataset.AC > 0) & (dataset.AC < 2 * dataset.n_called)).persist()

    n_variants = dataset.count_rows()
    if n_variants == 0:
        raise FatalError("Cannot run GRM: found 0 variants after filtering out monomorphic sites.")
    info("Computing GRM using {} variants.".format(n_variants))

    normalized_genotype_expr = functions.bind(
        dataset.AC / dataset.n_called,
        lambda mean_gt: functions.cond(functions.is_defined(dataset.GT),
                                       (dataset.GT.num_alt_alleles() - mean_gt) /
                                       functions.sqrt(mean_gt * (2 - mean_gt) * n_variants / 2),
                                       0))

    bm = BlockMatrix.from_matrix_table(normalized_genotype_expr)
    dataset.unpersist()
    grm = bm.T.dot(bm)

    return KinshipMatrix._from_block_matrix(dataset.colkey_schema,
                                      grm,
                                      [row.s for row in dataset.cols_table().select('s').collect()],
                                      n_variants)
