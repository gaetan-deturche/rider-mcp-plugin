using JetBrains.Application.Parts;
using JetBrains.Lifetimes;
using JetBrains.ProjectModel;
using JetBrains.Rd.Tasks;

namespace RiderMcp
{
    /// <summary>
    /// Backend host that implements the RD model declared in :protocol
    /// (RiderMcpModel). It answers requests coming from the Kotlin frontend's
    /// MCP tools — backend status snapshots and symbol resolution.
    ///
    /// The generated model type (RiderMcp.Model.RiderMcpModel) is produced by
    /// rdgen at build time. Wire its handlers once it exists:
    ///
    ///     var model = solution.GetProtocolSolution().GetRiderMcpModel();
    ///     model.GetBackendStatus.Set((lt, _) => BuildStatus(solution));
    ///     model.FindSymbols.Set((lt, query) => ResolveSymbols(solution, query));
    /// </summary>
    [SolutionComponent(Instantiation.DemandAnyThreadSafe)]
    public class RiderMcpHost
    {
        private readonly ISolution _solution;

        public RiderMcpHost(Lifetime lifetime, ISolution solution)
        {
            _solution = solution;
            // TODO: resolve generated model and register handlers here.
        }
    }
}
